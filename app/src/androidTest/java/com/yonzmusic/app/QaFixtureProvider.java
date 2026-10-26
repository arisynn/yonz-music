package com.yonzmusic.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;

/** Test APK only: Java avoids relying on the target APK's Kotlin runtime in this separate process. */
public final class QaFixtureProvider extends ContentProvider {
    private String mode = "online";
    private File audio() {
        File file = new File(getContext().getCacheDir(), "owned-provider.wav");
        if (!file.isFile()) try (InputStream in=getContext().getAssets().open("formats/owned-tone.wav"); OutputStream out=new FileOutputStream(file)) {
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>=0)out.write(buffer,0,n);
        } catch(IOException error) { throw new IllegalStateException(error); }
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "root".equals(uri.getLastPathSegment()) ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/wav"; }
    @Override public Bundle call(String method,String arg,Bundle extras) {
        if(!"mode".equals(method))throw new IllegalArgumentException();mode=arg==null?"online":arg;return new Bundle();
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
        if("permission".equals(mode))throw new SecurityException("Fixture permission revoked");
        if("offline".equals(mode))return null;
        String[] columns=projection!=null?projection:new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        MatrixCursor cursor=new MatrixCursor(columns);
        boolean child="children".equals(uri.getLastPathSegment());
        if("missing".equals(mode)&&child)return cursor;
        boolean isAudio=child||"root/audio".equals(uri.getLastPathSegment());File file=audio();Object[] row=new Object[columns.length];
        for(int i=0;i<columns.length;i++)switch(columns[i]) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID:row[i]=isAudio?"root/audio":"root";break;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME:row[i]=isAudio?"Own provider fixture.wav":"QA chosen source";break;
            case DocumentsContract.Document.COLUMN_MIME_TYPE:row[i]=isAudio?"audio/wav":DocumentsContract.Document.MIME_TYPE_DIR;break;
            case DocumentsContract.Document.COLUMN_SIZE:row[i]=isAudio?file.length():0L;break;
            case DocumentsContract.Document.COLUMN_LAST_MODIFIED:row[i]=file.lastModified();break;
            case DocumentsContract.Document.COLUMN_FLAGS:row[i]=0;break;
            default:row[i]=null;
        }
        cursor.addRow(row);return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String requestedMode) throws FileNotFoundException {
        if("permission".equals(mode))throw new SecurityException("Fixture permission revoked");
        if(!"online".equals(mode))throw new FileNotFoundException("Fixture offline");
        if(!"root/audio".equals(uri.getLastPathSegment())||!"r".equals(requestedMode))throw new FileNotFoundException();
        return ParcelFileDescriptor.open(audio(),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
