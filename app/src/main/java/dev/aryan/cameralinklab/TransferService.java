package dev.aryan.cameralinklab;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public final class TransferService extends Service {
    static final Set<Runnable> observers=new java.util.concurrent.CopyOnWriteArraySet<>();
    static volatile boolean busy;
    static volatile String summary="",currentKey="";
    static volatile int percent=-1;
    static volatile long completion;
    private static final AtomicReference<Request> pending=new AtomicReference<>();
    private volatile boolean cancelled;
    private CameraSession session;
    private PowerManager.WakeLock wake;
    private Thread worker;
    private volatile boolean completed;
    private static final int NOTIFICATION=71,RESULT_NOTIFICATION=72;

    static void start(Context context,CameraSession source,List<MediaItem> items,Uri destination,boolean newFolder,boolean lightroom)throws IOException {
        if(busy)throw new IOException("A transfer is already running");
        if(items.isEmpty())throw new IOException("No media selected");
        Context app=context.getApplicationContext();
        CameraSession copy=new CameraSession(source.root,source.network,source.user,source.password,s->GalleryLog.add(app,s));copy.identity=source.identity;
        Request request=new Request(copy,new ArrayList<>(items),destination,newFolder,lightroom);
        if(!pending.compareAndSet(null,request)){copy.close();throw new IOException("A transfer is starting");}
        busy=true;percent=-1;summary=lightroom?"Preparing Lightroom":"Starting download";
        try{context.startForegroundService(new Intent(context,TransferService.class));}
        catch(RuntimeException e){pending.compareAndSet(request,null);busy=false;copy.close();throw e;}
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"cancel".equals(intent.getAction())){cancelled=true;if(session!=null)session.close();return START_NOT_STICKY;}
        if(worker!=null)return START_NOT_STICKY;
        Request request=pending.getAndSet(null);if(request==null){stopSelf();return START_NOT_STICKY;}
        session=request.session;
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("original_downloads","Transfers",NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("transfer_results","Transfer results",NotificationManager.IMPORTANCE_DEFAULT));
        manager.cancel(RESULT_NOTIFICATION);
        startForeground(NOTIFICATION,notification(false),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"CameraRoll:download");wake.acquire(2*60*60*1000L);
        worker=new Thread(()->download(request),"camera-original-download");worker.start();return START_NOT_STICKY;
    }
    private void download(Request request){
        int saved=0,skipped=0;Uri partial=null;long totalBytes=0;MediaStateStore states=new MediaStateStore(this);
        try{
            if(request.lightroom){
                MediaItem item=request.items.get(0);currentKey=session.key(item);
                File original=OriginalCache.obtain(this,session,item,()->cancelled,(bytes,expected)->progress("Preparing Lightroom",bytes,expected));
                if(cancelled)throw new IOException("Transfer cancelled");
                Uri uri=LightroomProvider.stage(this,original,item);
                getSharedPreferences("gallery",0).edit().putString("handoff",uri.toString()).putString("handoffMime",item.mime()).putString("handoffKey",currentKey).commit();
                summary="Ready for Lightroom";percent=100;
            }else{
                Uri directory=DocumentsContract.buildDocumentUriUsingTree(request.destination,DocumentsContract.getTreeDocumentId(request.destination));
                if(request.newFolder){
                    String name="Camera "+new SimpleDateFormat("yyyy-MM-dd HH-mm-ss",Locale.US).format(new Date());
                    directory=DocumentsContract.createDocument(getContentResolver(),directory,DocumentsContract.Document.MIME_TYPE_DIR,name);
                    if(directory==null)throw new IOException("Cannot create download folder");
                }
                for(int i=0;i<request.items.size();i++){
                    if(cancelled)throw new IOException("Download cancelled");MediaItem item=request.items.get(i);currentKey=session.key(item);
                    if(!request.newFolder&&states.savedTo(currentKey,request.destination)){
                        try(android.database.Cursor receipt=getContentResolver().query(Uri.parse(states.savedUri(currentKey)),new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},Bundle.EMPTY,null)){if(receipt!=null&&receipt.moveToFirst()){skipped++;continue;}states.forgetSaved(currentKey);}
                        catch(Exception missing){GalleryLog.add(this,"Saved receipt unavailable: "+missing.getClass().getSimpleName()+": "+missing.getMessage());states.forgetSaved(currentKey);}
                    }
                    partial=DocumentsContract.createDocument(getContentResolver(),directory,item.mime(),item.name);
                    if(partial==null)throw new IOException("The selected folder is not writable");
                    final String title="Saving "+(i+1)+" of "+request.items.size();
                    CameraSession.SaveResult result;
                    try(OutputStream out=getContentResolver().openOutputStream(partial,"w")){
                        if(out==null)throw new IOException("Cannot open destination");
                        result=session.save(item,out,()->cancelled,(bytes,expected)->progress(title,bytes,expected));
                    }
                    states.saved(currentKey,partial,request.destination,result.bytes,result.hash);
                    totalBytes+=result.bytes;GalleryLog.add(this,"SAVED "+item.name+": "+result.bytes+" bytes; SHA-256 "+result.hash);
                    saved++;partial=null;changed();
                }
                summary="Saved "+saved+(saved==1?" original":" originals")+" • "+human(totalBytes)+(skipped>0?" • "+skipped+" already saved":"");percent=100;
            }
        }catch(Exception error){if(!currentKey.isEmpty())states.failed(currentKey);summary=(cancelled?"Cancelled":"Transfer stopped: "+error.getMessage())+(request.lightroom?"":" • "+saved+" saved");GalleryLog.add(this,summary);}
        finally{
            if(partial!=null)try{DocumentsContract.deleteDocument(getContentResolver(),partial);}catch(Exception e){GalleryLog.add(this,"Could not remove incomplete destination; remove it manually before retrying.");}
            completed=true;session.close();if(wake!=null&&wake.isHeld())wake.release();
            completion=System.currentTimeMillis();getSharedPreferences("gallery",0).edit().putString("lastTransfer",summary).putLong("completion",completion).apply();
            stopForeground(STOP_FOREGROUND_REMOVE);getSystemService(NotificationManager.class).notify(RESULT_NOTIFICATION,notification(true));stopSelf();
        }
    }
    private void progress(String title,long bytes,long expected){percent=expected>0?(int)Math.min(100,bytes*100/expected):-1;summary=title+" • "+human(bytes)+(expected>=0?" / "+human(expected):"");changed();}
    private static void notifyObservers(){Handler main=new Handler(Looper.getMainLooper());for(Runnable observer:observers)main.post(observer);}
    private void changed(){notifyObservers();getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(false));}
    private Notification notification(boolean terminal){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,GalleryActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP|Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=new Notification.Builder(this,terminal?"transfer_results":"original_downloads").setSmallIcon(R.drawable.ic_camera_link).setContentTitle("EOS Camera Roll").setContentText(summary).setContentIntent(open).setAutoCancel(terminal).setOngoing(!terminal).setOnlyAlertOnce(!terminal);
        if(!terminal){PendingIntent cancel=PendingIntent.getService(this,1,new Intent(this,TransferService.class).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);builder.setProgress(100,Math.max(0,percent),percent<0).addAction(new Notification.Action.Builder(null,"Cancel",cancel).build());}return builder.build();
    }
    static String human(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1024*1024)return String.format(Locale.US,"%.1f KB",bytes/1024.0);if(bytes<1024L*1024*1024)return String.format(Locale.US,"%.1f MB",bytes/(1024.0*1024));return String.format(Locale.US,"%.2f GB",bytes/(1024.0*1024*1024));}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){cancelled=true;if(session!=null)session.close();if(wake!=null&&wake.isHeld())wake.release();if(!completed)summary="Transfer interrupted. Completed originals are kept.";busy=false;currentKey="";notifyObservers();super.onDestroy();}
    private static final class Request{
        final CameraSession session;final List<MediaItem> items;final Uri destination;final boolean newFolder,lightroom;
        Request(CameraSession session,List<MediaItem> items,Uri destination,boolean newFolder,boolean lightroom){this.session=session;this.items=items;this.destination=destination;this.newFolder=newFolder;this.lightroom=lightroom;}
    }
}
