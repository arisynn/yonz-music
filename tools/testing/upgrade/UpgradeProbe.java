package com.yonzmusic.upgrade;

import android.app.Instrumentation;
import android.os.Bundle;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;

/** Private, same-signed QA instrumentation. Never distribute this APK with the application. */
public final class UpgradeProbe extends Instrumentation {
    private String mode;
    private static final String ID="qa-streaming-upgrade";
    private static final long PLAYLIST=900000000001L;
    @Override public void onCreate(Bundle args) {super.onCreate(args);mode=args.getString("mode","verify");start();}
    private String scalar(SQLiteDatabase db,String sql) {
        try(Cursor c=db.rawQuery(sql,null)) {if(!c.moveToFirst())throw new AssertionError("Missing fixture row");return c.getString(0);}
    }
    private void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            android.content.Intent launch=getTargetContext().getPackageManager().getLaunchIntentForPackage("com.yonzmusic.app");
            if(launch==null)throw new AssertionError("Target launcher missing");
            startActivitySync(launch);
            String path=getTargetContext().getDatabasePath("yonz-music.db").getPath();
            int expected="seed".equals(mode)?3:4;
            long deadline=android.os.SystemClock.elapsedRealtime()+60000;
            boolean ready=false;
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                if(new java.io.File(path).isFile()) {
                    try(SQLiteDatabase check=SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READWRITE)) {
                        if(Integer.parseInt(scalar(check,"PRAGMA user_version"))==expected) {ready=true;break;}
                    }
                }
                android.os.SystemClock.sleep(100);
            }
            check(ready,"Target Room schema did not open");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(path,null,SQLiteDatabase.OPEN_READWRITE)) {
                int version=Integer.parseInt(scalar(db,"PRAGMA user_version"));
                if("seed".equals(mode)) {
                    check(version==3,"Baseline must use Room3");
                    db.beginTransaction();
                    try {
                        db.execSQL("INSERT OR REPLACE INTO tracks (id,uri,title,artist,album,folder,fileName,duration,added,artUri,source,available,active) VALUES (?, 'content://com.yonzmusic.qa.fixture/document/root%2Faudio','Owned upgrade fixture','QA owner','QA album','QA folder','owned.wav',5000,1,'','saf',1,1)",new Object[]{ID});
                        db.execSQL("INSERT OR REPLACE INTO favorites VALUES (?)",new Object[]{ID});
                        db.execSQL("INSERT OR REPLACE INTO history VALUES (?,123,456)",new Object[]{ID});
                        db.execSQL("INSERT OR REPLACE INTO playlists VALUES (?,'Owned upgrade playlist',1)",new Object[]{PLAYLIST});
                        db.execSQL("INSERT OR REPLACE INTO playlist_entries VALUES (?,?,0)",new Object[]{PLAYLIST,ID});
                        db.execSQL("INSERT OR REPLACE INTO app_state VALUES ('qa-upgrade-marker','retained-exactly')");
                        db.setTransactionSuccessful();
                    }finally {db.endTransaction();}
                } else if("verify".equals(mode)) {
                    check(version==4,"Candidate must use Room4");
                    check("Owned upgrade fixture".equals(scalar(db,"SELECT title FROM tracks WHERE id='"+ID+"'")),"Track preserved");
                    check(ID.equals(scalar(db,"SELECT trackId FROM favorites WHERE trackId='"+ID+"'")),"Favorite preserved");
                    check("456".equals(scalar(db,"SELECT position FROM history WHERE trackId='"+ID+"'")),"History position preserved");
                    check(ID.equals(scalar(db,"SELECT trackId FROM playlist_entries WHERE playlistId="+PLAYLIST)),"Playlist/order preserved");
                    check("retained-exactly".equals(scalar(db,"SELECT value FROM app_state WHERE name='qa-upgrade-marker'")),"State preserved");
                    check("0".equals(scalar(db,"SELECT COUNT(*) FROM online_activity")),"New activity table starts empty");
                } else {throw new AssertionError("Unknown QA operation");}
                result.putInt("schema",version);
            }
            result.putBoolean("passed",true);result.putString("operation",mode);finish(-1,result);
        } catch(Throwable error) {
            result.putBoolean("passed",false);result.putString("operation",mode);result.putString("error",error.toString());finish(0,result);
        }
    }
}
