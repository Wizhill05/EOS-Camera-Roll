package dev.aryan.cameralinklab;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.JSONObject;

final class GalleryImages implements AutoCloseable {
    private final CameraSession session;
    private final Context context;
    private final ThumbnailStore disk;
    private final Consumer<String> changed;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor thumbs=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(48));
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>()),failed=Collections.synchronizedSet(new HashSet<>()),validated=Collections.synchronizedSet(new HashSet<>());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>((int)Math.min(40*1024*1024,Runtime.getRuntime().maxMemory()/8)){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    volatile boolean paused,closed,networkEnabled=true;
    private volatile int previewGeneration;
    private volatile long rawJob;
    GalleryImages(Context context,CameraSession session,Runnable changed){this(context,session,key->changed.run());}
    GalleryImages(Context context,CameraSession session,Consumer<String> changed){this.context=context.getApplicationContext();this.session=session;this.changed=changed;disk=new ThumbnailStore(context);}
    float ratio(MediaItem item){return disk.ratio(session.key(item));}
    void refresh(){networkEnabled=true;failed.clear();validated.clear();}

    void thumbnail(MediaItem item,ImageView view){
        String key=session.key(item);view.setTag(key);Bitmap hit=cache.get(key);if(hit==null)view.setImageDrawable(null);else view.setImageBitmap(hit);
        if(closed||paused||failed.contains(key)||hit!=null&&(!networkEnabled||validated.contains(key))||!pending.add(key))return;
        try{thumbs.execute(()->{
            try{
                if(closed||paused)return;
                Bitmap saved=cache.get(key);if(saved==null){saved=disk.read(key);if(saved!=null){cache.put(key,saved);deliver(key,saved,view);}}
                if(!networkEnabled)return;
                JSONObject info=null;String revision="";
                try{Object value=session.json(MediaCatalog.query(item.url,"kind=info"));if(value instanceof JSONObject){info=(JSONObject)value;revision=ThumbnailStore.revision(info);}}
                catch(CameraHttp.StatusException unsupported){if(unsupported.status!=400&&unsupported.status!=404&&unsupported.status!=405)throw unsupported;}
                if(saved!=null&&(revision.isEmpty()||revision.equals(disk.revision(key)))){validated.add(key);return;}
                // A missing or changed cache entry must not inherit another file revision's orientation.
                cache.remove(key);disk.invalidate(key);
                byte[] bytes=session.bytes(MediaCatalog.query(item.url,"kind=thumbnail"),8*1024*1024);
                int fallback=disk.orientation(key);
                if(!item.video&&ExifOrientation.read(bytes)<=1&&fallback==0){
                    if(info!=null){int degrees=info.optInt("rotate",info.optInt("rotation",-1));fallback=ExifOrientation.fromDegrees(degrees);}
                    if(fallback<=1)try{int originalOrientation=ExifOrientation.read(session.originalHeader(item,65536));if(originalOrientation>0)fallback=originalOrientation;}catch(IOException unavailable){session.log.accept("Original orientation unavailable for "+item.name);}
                    disk.orientation(key,fallback);
                }
                Bitmap image=decode(bytes,640,fallback);
                if(closed){image.recycle();return;}disk.write(key,image);disk.revision(key,revision);cache.put(key,image);validated.add(key);deliver(key,image,view);
            }catch(Exception error){if(!closed){failed.add(key);session.log.accept("Thumbnail unavailable for "+item.name+": "+error.getMessage());}}
            finally{pending.remove(key);}
        });}catch(RejectedExecutionException full){pending.remove(key);}
    }
    private void deliver(String key,Bitmap bitmap,ImageView view){main.post(()->{if(!closed){if(key.equals(view.getTag()))view.setImageBitmap(bitmap);changed.accept(key);}});}

    void preview(MediaItem item,Consumer<Bitmap> loaded,Consumer<String> note,BiConsumer<Long,Long> progress){
        cancelPreview();final int token=previewGeneration;String key=session.key(item);Bitmap hit=cache.get(key);if(hit!=null)loaded.accept(hit);
        previews.execute(()->{
            BooleanSupplier cancelled=()->closed||token!=previewGeneration;
            try{
                Bitmap saved=disk.read(key);if(saved!=null)main.post(()->{if(!cancelled.getAsBoolean())loaded.accept(saved);});
                if(cancelled.getAsBoolean())return;
                if(networkEnabled){
                    try{Bitmap quick=decode(session.bytes(MediaCatalog.query(item.url,item.video?"kind=thumbnail":"kind=display"),32*1024*1024),2560,disk.orientation(key));main.post(()->{if(!cancelled.getAsBoolean()){loaded.accept(quick);note.accept(item.video?"Ready to play":"Preview");}});}catch(IOException ignored){}
                }
                if(item.video){if(!networkEnabled)main.post(()->{if(!cancelled.getAsBoolean())note.accept("Refresh to reconnect and play this video");});return;}
                File original=networkEnabled?OriginalCache.obtain(context,session,item,cancelled,(n,total)->main.post(()->{if(!cancelled.getAsBoolean())progress.accept(n,total);})):OriginalCache.existing(context,session,item);
                if(original==null)throw new IOException("Refresh to reconnect for the original");
                if(cancelled.getAsBoolean())return;cache.evictAll();Bitmap full;
                if(item.raw()){
                    long job=RawRenderer.job();rawJob=job;if(cancelled.getAsBoolean()){RawRenderer.cancel(job);return;}
                    main.post(()->{if(!cancelled.getAsBoolean())note.accept("Rendering RAW…");});full=RawRenderer.render(original,job);
                }else{
                    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(original.getPath(),bounds);
                    if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>64000000L)throw new IOException("Full preview unavailable for this image");
                    full=BitmapFactory.decodeFile(original.getPath());if(full==null)throw new IOException("Phone cannot decode this format");
                    full=orient(full,new ExifInterface(original.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION,1));
                }
                Bitmap rendered=full;main.post(()->{if(!cancelled.getAsBoolean()){loaded.accept(rendered);note.accept("Full resolution  "+rendered.getWidth()+" × "+rendered.getHeight());progress.accept(-1L,-1L);}else rendered.recycle();});
            }catch(Exception|OutOfMemoryError error){main.post(()->{if(!cancelled.getAsBoolean()){note.accept("Preview only • "+(error instanceof OutOfMemoryError?"Not enough memory":error.getMessage()));progress.accept(-1L,-1L);}});}
        });
    }
    void cancelPreview(){previewGeneration++;if(rawJob>0)RawRenderer.cancel(rawJob);}
    static Bitmap orient(Bitmap bitmap,int orientation){
        Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(270);matrix.postScale(-1,1);break;case 8:matrix.setRotate(270);break;default:return bitmap;}
        Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap)bitmap.recycle();return rotated;
    }
    static Bitmap decode(byte[] bytes,int maximum,int fallback)throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>100000||bounds.outHeight>100000)throw new IOException("Camera did not return a supported image");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>maximum)options.inSampleSize*=2;
        Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new IOException("Image cannot be decoded");
        return orient(image,ExifOrientation.effective(ExifOrientation.read(bytes),fallback,image.getWidth(),image.getHeight()));
    }
    @Override public void close(){closed=true;cancelPreview();thumbs.shutdownNow();previews.shutdownNow();cache.evictAll();}
}
