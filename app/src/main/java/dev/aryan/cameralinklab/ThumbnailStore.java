package dev.aryan.cameralinklab;

import android.content.*;
import android.graphics.*;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;
import org.json.JSONObject;

final class ThumbnailStore {
    private final File directory;private final SharedPreferences meta;private int writes;
    ThumbnailStore(Context context){directory=new File(context.getCacheDir(),"upright-thumbnails-v1");directory.mkdirs();meta=context.getSharedPreferences("thumbnail-meta-v1",0);}
    Bitmap read(String key){File file=new File(directory,key+".jpg");if(!file.isFile())return null;Bitmap image=BitmapFactory.decodeFile(file.getPath());if(image!=null)file.setLastModified(System.currentTimeMillis());else file.delete();return image;}
    void write(String key,Bitmap image)throws IOException{
        AtomicFile file=new AtomicFile(new File(directory,key+".jpg"));FileOutputStream stream=file.startWrite();
        try{if(!image.compress(Bitmap.CompressFormat.JPEG,90,stream))throw new IOException("Could not cache thumbnail");file.finishWrite(stream);}catch(Exception error){file.failWrite(stream);throw error;}
        meta.edit().putInt("w_"+key,image.getWidth()).putInt("h_"+key,image.getHeight()).apply();if(++writes%24==0)trim();
    }
    float ratio(String key){int width=meta.getInt("w_"+key,0),height=meta.getInt("h_"+key,0);return width>0&&height>0?width/(float)height:1.25f;}
    int orientation(String key){return meta.getInt("o_"+key,0);}
    void orientation(String key,int orientation){if(orientation>0)meta.edit().putInt("o_"+key,orientation).apply();}
    String revision(String key){return meta.getString("r_"+key,"");}
    void revision(String key,String revision){meta.edit().putString("r_"+key,revision).apply();}
    static String revision(JSONObject info){return info.optString("filesize","")+":"+info.optString("lastmodifieddate",info.optString("datetime",""))+":"+info.optString("rotate",info.optString("rotation",""));}
    void invalidate(String key){new File(directory,key+".jpg").delete();meta.edit().remove("o_"+key).remove("r_"+key).apply();}
    private void trim(){File[] files=directory.listFiles((dir,name)->name.endsWith(".jpg"));if(files==null)return;long bytes=0;for(File file:files)bytes+=file.length();Arrays.sort(files,Comparator.comparingLong(File::lastModified));for(File file:files){if(bytes<=96L*1024*1024)break;long size=file.length();if(file.delete())bytes-=size;}}
}
