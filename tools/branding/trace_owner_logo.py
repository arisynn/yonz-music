"""Reproduce the owner's supplied YONO monogram as vectors; never invent a replacement mark.
Development-only: Pillow 12.3.0, vtracer 0.6.12. No runtime dependency.
Thresholds separate the two flat ink colors from the supplied mist-blue JPEG.
"""
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET
from PIL import Image
import vtracer

root = Path(__file__).resolve().parents[2]
reference = root / 'brand/logo-reference.jpg'
image = Image.open(reference).convert('RGB')
assert image.size == (1280, 1280)
layers = []
with tempfile.TemporaryDirectory() as temp:
    for name, color in [('ink', '#315673'), ('accent', '#88B3CD')]:
        mask = Image.new('RGB', image.size, 'white')
        pixels = []
        for r, g, b in image.getdata():
            dark = r < 100 and g < 150 and b < 185
            accent = 105 <= r < 180 and 145 <= g < 205 and b > 175 and g - r > 12
            selected = dark if name == 'ink' else accent
            pixels.append((0, 0, 0) if selected else (255, 255, 255))
        mask.putdata(pixels)
        png, svg = Path(temp) / (name + '.png'), Path(temp) / (name + '.svg')
        mask.save(png)
        vtracer.convert_image_to_svg_py(str(png), str(svg), colormode='binary', mode='spline', filter_speckle=1, corner_threshold=60, length_threshold=.5, max_iterations=20, splice_threshold=10, path_precision=4)
        tree = ET.parse(svg).getroot()
        paths = []
        for path in tree.iter('{http://www.w3.org/2000/svg}path'):
            if path.attrib.get('d', '').strip():
                paths.append((path.attrib['d'], path.attrib.get('transform', '')))
        layers.append((color, paths))

# The complete mark lies inside the launcher's central 66dp circle, including the note flag.
svg_paths = []
vector_paths = []
mono_paths = []
for color, paths in layers:
    for d, transform in paths:
        svg_paths.append(f'<path fill="{color}" d="{d}" transform="{transform}"/>')
        # vtracer uses local translated paths; preserve those translations exactly.
        import re
        match = re.fullmatch(r'translate\(([-.0-9]+),([-.0-9]+)\)', transform)
        assert match, transform
        x, y = match.groups()
        vector_paths.append(f'<group android:translateX="{x}" android:translateY="{y}"><path android:fillColor="{color}" android:fillType="evenOdd" android:pathData="{d}"/></group>')
        mono_paths.append(f'<group android:translateX="{x}" android:translateY="{y}"><path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="{d}"/></group>')

symbol = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1280 1280">' + ''.join(svg_paths) + '</svg>'
(root / 'brand/logo-symbol.svg').write_text(symbol)
(root / 'brand/logo-symbol-light.svg').write_text(symbol)
(root / 'brand/logo-monochrome.svg').write_text(symbol.replace('#315673', '#315673').replace('#88B3CD', '#315673'))
preview = '<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 108 108"><rect width="108" height="108" rx="24" fill="#CFEAF5"/><g transform="translate(18.81,21.156) scale(.051)">' + ''.join(svg_paths) + '</g></svg>'
(root / 'brand/launcher-preview.svg').write_text(preview)
(root / 'brand/identity-preview.svg').write_text('<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="1280" viewBox="0 0 1280 1280"><rect width="1280" height="1280" rx="170" fill="#CFEAF5"/>' + ''.join(svg_paths) + '</svg>')
header = '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">'
group = '<group android:translateX="18.81" android:translateY="21.156" android:scaleX="0.051" android:scaleY="0.051">'
(root / 'app/src/main/res/drawable/brand_y.xml').write_text(header + group + ''.join(vector_paths) + '</group></vector>\n')
(root / 'app/src/main/res/drawable/brand_symbol.xml').write_text('<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="128dp" android:height="128dp" android:viewportWidth="1280" android:viewportHeight="1280">' + ''.join(vector_paths) + '</vector>\n')
(root / 'app/src/main/res/drawable/brand_monochrome.xml').write_text(header + group + ''.join(mono_paths) + '</group></vector>\n')
(root / 'app/src/main/res/drawable/brand_background.xml').write_text('<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="#CFEAF5"/></shape>\n')
print('Owner logo traced into color/monochrome vectors and safe-zone launcher assets.')
