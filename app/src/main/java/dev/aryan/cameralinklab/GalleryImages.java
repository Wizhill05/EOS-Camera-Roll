package dev.aryan.cameralinklab;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.HttpURLConnection;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final AtomicLong sequence=new AtomicLong();
    private final ThreadPoolExecutor thumbs=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new WorkQueue());
    private volatile CameraHttp activeThumbnail;
    private volatile int thumbnailEpoch;
    private volatile boolean rotationRequests=true;
    private final Set<String> noRotation=ConcurrentHashMap.newKeySet();
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private final Map<String,WeakReference<ImageView>> targets=new ConcurrentHashMap<>();
    private final Set<String> pending=Collections.synchronizedSet(new HashSet<>()),failed=Collections.synchronizedSet(new HashSet<>());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>((int)Math.min(40*1024*1024,Runtime.getRuntime().maxMemory()/8)){
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };
    private volatile boolean paused;
    volatile boolean closed,networkEnabled=true;
    private volatile int previewGeneration;
    private volatile long rawJob;
    GalleryImages(Context context,CameraSession session,Runnable changed){this(context,session,key->changed.run());}
    GalleryImages(Context context,CameraSession session,Consumer<String> changed){this.context=context.getApplicationContext();this.session=session;this.changed=changed;disk=new ThumbnailStore(context);}
    float ratio(MediaItem item){return disk.ratio(session.key(item));}
    void refresh(){networkEnabled=true;failed.clear();rotationRequests=true;noRotation.clear();}
    void setPaused(boolean value){
        paused=value;if(!value)return;thumbnailEpoch++;pending.clear();
        for(Runnable queued:thumbs.getQueue().toArray(new Runnable[0]))if(thumbs.remove(queued))((Work)queued).discard();
        CameraHttp client=activeThumbnail;activeThumbnail=null;if(client!=null)CompletableFuture.runAsync(client::close);
    }
    void recycle(ImageView view){Object key=view.getTag();if(key instanceof String)targets.computeIfPresent((String)key,(k,target)->target.get()==view?null:target);view.setTag(null);}
    private ImageView target(String key){WeakReference<ImageView> reference=targets.get(key);ImageView view=reference==null?null:reference.get();return view!=null&&key.equals(view.getTag())?view:null;}
    private final class Work implements Runnable,Comparable<Work>{
        final int priority,epoch;final long order=sequence.incrementAndGet();final String key;final Runnable action;
        Work(int priority,String key,Runnable action){this.priority=priority;this.key=key;this.action=action;epoch=thumbnailEpoch;}
        boolean current(){return !closed&&!paused&&epoch==thumbnailEpoch&&target(key)!=null;}
        @Override public int compareTo(Work other){int difference=Integer.compare(priority,other.priority);return difference!=0?difference:Long.compare(order,other.order);}
        @Override public void run(){try{if(current())action.run();}finally{discard();}}
        void discard(){if(priority==0)pending.remove(key+"@"+epoch);}
    }
    private static final class WorkQueue extends PriorityBlockingQueue<Runnable>{
        @Override public synchronized boolean offer(Runnable work){if(size()>=16)return false;return super.offer(work);}
    }
    private void enqueue(Work work){
        if(thumbs.getQueue().size()>=16)for(Runnable queued:thumbs.getQueue().toArray(new Runnable[0])){Work old=(Work)queued;if(!old.current()||work.priority==0&&old.priority>0&&thumbs.getQueue().size()>=16)if(thumbs.remove(old))old.discard();}
        try{thumbs.execute(work);}catch(RejectedExecutionException full){work.discard();}
    }
    private byte[] readSmall(MediaItem item,String kind,int maximum,int timeout,int epoch)throws IOException{
        if(closed||paused||!networkEnabled||epoch!=thumbnailEpoch)throw new IOException("Thumbnail cancelled");
        if(!CameraHttp.sameOrigin(session.root,item.url))throw new IOException("Invalid camera origin");
        CameraHttp client=session.client().timeouts(1500,timeout);activeThumbnail=client;
        try{
            if(closed||paused||epoch!=thumbnailEpoch)throw new IOException("Thumbnail cancelled");
            HttpURLConnection connection=client.get(MediaCatalog.query(item.url,"kind="+kind));
            try(InputStream in=connection.getInputStream()){return CameraHttp.readBounded(in,maximum);}finally{connection.disconnect();}
        }finally{if(activeThumbnail==client)activeThumbnail=null;session.clients.remove(client);client.close();}
    }
    void thumbnail(MediaItem item,ImageView view){
        String key=session.key(item);if(!key.equals(view.getTag()))recycle(view);view.setTag(key);targets.put(key,new WeakReference<>(view));Bitmap hit=cache.get(key);if(hit!=null){view.setImageBitmap(hit);return;}view.setImageDrawable(null);
        final int epoch=thumbnailEpoch;
        if(closed||paused||failed.contains(key)||!pending.add(key+"@"+epoch))return;
        enqueue(new Work(0,key,()->{
            try{
                Bitmap saved=disk.read(key);if(saved!=null){cache.put(key,saved);deliver(key,saved,epoch);return;}
                if(!networkEnabled||target(key)==null)return;
                byte[] bytes=readSmall(item,"thumbnail",2*1024*1024,4000,epoch);
                if(closed||paused||epoch!=thumbnailEpoch||target(key)==null)return;
                Bitmap image=decode(bytes,640,0);disk.orientation(key,0);disk.write(key,image);cache.put(key,image);deliver(key,image,epoch);
                if(!item.video&&ExifOrientation.read(bytes)<=1)enqueue(new Work(1,key,()->completeOrientation(item,key,bytes,epoch)));
            }catch(Exception error){if(!closed&&!paused&&epoch==thumbnailEpoch&&networkEnabled&&target(key)!=null){failed.add(key);session.log.accept("Thumbnail unavailable for "+item.name+": "+error.getMessage());}}
        }));
    }
    private void completeOrientation(MediaItem item,String key,byte[] bytes,int epoch){
        if(!networkEnabled||!rotationRequests||noRotation.contains(item.extension))return;
        try{
            JSONObject info=new JSONObject(new String(readSmall(item,"info",65536,1000,epoch),StandardCharsets.UTF_8));
            int orientation=ExifOrientation.fromDegrees(info.optInt("rotate",info.optInt("rotation",-1)));
            if(orientation==0){noRotation.add(item.extension);return;}
            if(closed||paused||epoch!=thumbnailEpoch||target(key)==null)return;
            disk.orientation(key,orientation);
            if(orientation<=1)return;
            Bitmap image=decode(bytes,640,orientation);disk.write(key,image);cache.put(key,image);deliver(key,image,epoch);
        }catch(Exception unavailable){if(!closed&&!paused&&epoch==thumbnailEpoch){rotationRequests=false;session.log.accept("Camera thumbnail rotation unavailable for "+item.name+"; original metadata is read only when opened.");}}
    }
    private void deliver(String key,Bitmap bitmap,int epoch){main.post(()->{if(!closed&&epoch==thumbnailEpoch){ImageView view=target(key);if(view!=null)view.setImageBitmap(bitmap);changed.accept(key);}});}
    private void rememberPreview(MediaItem item,Bitmap rendered,File original)throws IOException{
        String key=session.key(item);float factor=Math.min(1,640f/Math.max(rendered.getWidth(),rendered.getHeight()));Bitmap thumbnail=Bitmap.createScaledBitmap(rendered,Math.max(1,Math.round(rendered.getWidth()*factor)),Math.max(1,Math.round(rendered.getHeight()*factor)),true);
        if(thumbnail==rendered)thumbnail=rendered.copy(Bitmap.Config.ARGB_8888,false);disk.write(key,thumbnail);cache.put(key,thumbnail);
        try(InputStream in=new FileInputStream(original)){byte[] header=new byte[65536];int count=0,n;while(count<header.length&&(n=in.read(header,count,header.length-count))>0)count+=n;disk.orientation(key,ExifOrientation.read(Arrays.copyOf(header,count)));}
        main.post(()->{if(!closed)changed.accept(key);});
    }

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
                Bitmap rendered=full;try{rememberPreview(item,rendered,original);}catch(IOException cacheError){session.log.accept("Could not cache the opened photo thumbnail");}main.post(()->{if(!cancelled.getAsBoolean()){loaded.accept(rendered);note.accept("Full resolution  "+rendered.getWidth()+" × "+rendered.getHeight());progress.accept(-1L,-1L);}else rendered.recycle();});
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
    @Override public void close(){closed=true;setPaused(true);cancelPreview();thumbs.shutdownNow();previews.shutdownNow();targets.clear();cache.evictAll();}
}
