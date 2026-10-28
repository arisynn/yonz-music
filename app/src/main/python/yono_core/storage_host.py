"""Host-only reference store for tests/demo; Android uses SafDocumentStore instead."""
from pathlib import Path
import os
import shutil
from urllib.parse import unquote, urlsplit
from .engine import atomic_json
from .media import file_hash
from .model import safe_name


class DirectoryStore:
    def __init__(self, selected_root, receipts):
        self.root = Path(selected_root).resolve()
        self.receipts = Path(receipts)
        self.root.mkdir(parents=True, exist_ok=True)
        self.receipts.mkdir(parents=True, exist_ok=True)

    def path(self, uri):
        parsed = urlsplit(str(uri))
        path = Path(unquote(parsed.path)).resolve()
        if parsed.scheme != "file" or not path.is_relative_to(self.root):
            raise ValueError("not inside selected host root")
        return path

    def exists(self, uri):
        try:
            return self.path(uri).is_file()
        except ValueError:
            return False

    def matches(self, uri, digest):
        return self.exists(uri) and file_hash(self.path(uri)) == digest

    def _receipt(self, key):
        if not key or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-" for c in key):
            raise ValueError("invalid receipt key")
        return self.receipts / (key + ".json")

    def lookup(self, key, digest):
        import json
        try:
            row = json.loads(self._receipt(key).read_text())
            if row.get("sha256") == digest and self.matches(row["uri"], digest):
                return row["uri"]
        except (OSError, ValueError, KeyError):
            pass
        return ""

    def commit(self, path, folder, filename, mime, key, digest):
        return self._commit(path, folder, filename, mime, key, digest)

    def commitCover(self, path, folder, filename, mime, key, digest):
        parent = self.root / safe_name(folder) if folder else self.root
        cover = parent / ".cover"
        cover.mkdir(parents=True, exist_ok=True)
        (cover / ".nomedia").touch(exist_ok=True)
        return self._commit(path, folder, filename, mime, key, digest, cover=True)

    def _commit(self, path, folder, filename, mime, key, digest, cover=False):
        prior = self.lookup(key, digest)
        if prior:
            return prior
        path = Path(path)
        if file_hash(path) != digest:
            raise ValueError("input checksum differs")
        parent = self.root / safe_name(folder) if folder else self.root
        if cover:
            parent = parent / ".cover"
        elif mime == "application/json" and filename.endswith(".yono.json"):
            parent = parent / ".yono"
        parent.mkdir(parents=True, exist_ok=True)
        name = Path(safe_name(filename, 180))
        for i in range(1, 10001):
            target = parent / (name.name if i == 1 else f"{name.stem} ({i}){name.suffix}")
            try:
                fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                break
            except FileExistsError:
                continue
        else:
            raise ValueError("too many collisions")
        try:
            with os.fdopen(fd, "wb") as output, path.open("rb") as source:
                shutil.copyfileobj(source, output)
                output.flush()
                os.fsync(output.fileno())
            if file_hash(target) != digest:
                raise ValueError("output checksum differs")
            uri = target.as_uri()
            atomic_json(self._receipt(key), {"uri": uri, "sha256": digest})
            return uri
        except BaseException:
            target.unlink(missing_ok=True)
            raise

    def copyToPrivate(self, uri, workspace):
        source = self.path(uri)
        target = Path(workspace) / ("source" + source.suffix.lower())
        shutil.copyfile(source, target)
        return str(target)
