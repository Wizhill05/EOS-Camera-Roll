package dev.aryan.cameralinklab;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class LibrarySnapshot {
    final URL root;final String identity;final List<MediaItem> items;final long updated;
    LibrarySnapshot(URL root,String identity,List<MediaItem> items,long updated){this.root=root;this.identity=identity;this.items=new ArrayList<>(items);this.updated=updated;}
    static LibrarySnapshot read(Context context)throws IOException{
        AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"library-v1.json"));
        try(InputStream in=file.openRead()){
            JSONObject json=new JSONObject(new String(CameraHttp.readBounded(in,8*1024*1024),StandardCharsets.UTF_8));
            URL root=CameraHttp.url(json.getString("camera"));JSONArray paths=json.getJSONArray("paths");if(paths.length()>100000)throw new IOException("Cached library is too large");
            List<MediaItem> items=new ArrayList<>();for(int i=0;i<paths.length();i++){
                URL item=new URL(root,paths.getString(i));if(!CameraHttp.sameOrigin(root,item)||!item.getPath().matches("/ccapi/ver[0-9]+/contents/.+")||!MediaItem.isMedia(item.getPath())||item.getUserInfo()!=null||item.getQuery()!=null||item.getRef()!=null)throw new IOException("Invalid cached camera path");items.add(new MediaItem(item));
            }
            return new LibrarySnapshot(root,json.optString("identity","address:"+root.getHost()),items,json.optLong("updated",0));
        }catch(JSONException e){throw new IOException("Cached library is unreadable",e);}
    }
    void write(Context context)throws IOException{
        JSONArray paths=new JSONArray();for(MediaItem item:items)paths.put(item.url.getPath());
        try{
            JSONObject json=new JSONObject().put("camera",root.toString()).put("identity",identity).put("updated",updated).put("paths",paths);
            AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"library-v1.json"));FileOutputStream stream=file.startWrite();
            try{stream.write(json.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream);}catch(Exception e){file.failWrite(stream);throw e;}
        }catch(JSONException e){throw new IOException("Could not save cached library",e);}
    }
}
