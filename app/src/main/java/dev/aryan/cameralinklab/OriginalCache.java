package dev.aryan.cameralinklab;

import android.content.Context;
import java.io.*;
import java.util.*;
import java.util.function.*;
import org.json.JSONObject;

final class OriginalCache {
    static synchronized File obtain(Context context,CameraSession session,MediaItem item,BooleanSupplier cancelled,BiConsumer<Long,Long> progress)throws IOException {
        if(cancelled.getAsBoolean())throw new IOException("Cancelled");
        File root=new File(context.getCacheDir(),"originals");root.mkdirs();File directory=new File(root,session.key(item));directory.mkdirs();
        File original=new File(directory,"original"),ready=new File(directory,"ready");
        String revision="";
        try{Object info=session.json(MediaCatalog.query(item.url,"kind=info"));if(info instanceof JSONObject){JSONObject json=(JSONObject)info;revision=json.optString("filesize","")+":"+json.optString("lastmodifieddate","");}}catch(IOException ignored){}
        if(original.isFile()&&ready.isFile())try(DataInputStream in=new DataInputStream(new FileInputStream(ready))){
            long length=in.readLong();String stamp=in.readUTF();
            if(length==original.length()&&(revision.isEmpty()||revision.equals(stamp))){original.setLastModified(System.currentTimeMillis());progress.accept(length,length);return original;}
        }catch(IOException ignored){}
        ready.delete();File partial=new File(directory,"partial-"+UUID.randomUUID());
        try{
            CameraSession.SaveResult result;
            try(OutputStream out=new FileOutputStream(partial)){result=session.save(item,out,cancelled,progress);}
            if(cancelled.getAsBoolean())throw new IOException("Cancelled");
            if(original.exists()&&!original.delete())throw new IOException("Cannot replace cached original");
            if(!partial.renameTo(original))throw new IOException("Cannot finish original cache");
            try(DataOutputStream out=new DataOutputStream(new FileOutputStream(ready))){out.writeLong(result.bytes);out.writeUTF(revision);}
            trim(root,directory);return original;
        }finally{partial.delete();}
    }
    static File existing(Context context,CameraSession session,MediaItem item){
        File directory=new File(new File(context.getCacheDir(),"originals"),session.key(item));File file=new File(directory,"original");
        return new File(directory,"ready").isFile()&&file.isFile()?file:null;
    }
    static void cleanup(Context context){
        File root=new File(context.getCacheDir(),"originals");File[] directories=root.listFiles();if(directories==null)return;File newest=null;long recent=0;
        for(File directory:directories){File[] partials=directory.listFiles((dir,name)->name.startsWith("partial-"));if(partials!=null)for(File partial:partials)partial.delete();File original=new File(directory,"original");if(!original.isFile())new File(directory,"ready").delete();else if(original.lastModified()>=recent){recent=original.lastModified();newest=directory;}}
        if(newest!=null)trim(root,newest);
    }
    private static void trim(File root,File keep){
        File[] directories=root.listFiles();if(directories==null)return;Arrays.sort(directories,Comparator.comparingLong(d->new File(d,"original").lastModified()));long bytes=0;
        for(File dir:directories)bytes+=new File(dir,"original").length();
        for(File dir:directories)if(bytes>160L*1024*1024&&!dir.equals(keep)){File file=new File(dir,"original");long length=file.length();if(file.delete()){new File(dir,"ready").delete();dir.delete();bytes-=length;}}
    }
}
