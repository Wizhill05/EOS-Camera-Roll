package dev.aryan.cameralinklab;

import android.app.Application;
import java.io.File;

public final class MediaApplication extends Application {
    @Override public void onCreate(){
        super.onCreate();
        LightroomProvider.cleanup(this);
        OriginalCache.cleanup(this);
        File[] interruptedBuffers=getCacheDir().listFiles((directory,name)->name.matches("video-[0-9a-f-]+\\.buffer"));
        if(interruptedBuffers!=null)for(File buffer:interruptedBuffers)buffer.delete();
    }
}
