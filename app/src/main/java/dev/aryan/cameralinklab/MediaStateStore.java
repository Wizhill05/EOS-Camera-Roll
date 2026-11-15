package dev.aryan.cameralinklab;

import android.content.*;
import android.net.Uri;

final class MediaStateStore {
    private final SharedPreferences preferences;
    MediaStateStore(Context context){preferences=context.getSharedPreferences("media-state",0);}
    int rating(String key){return preferences.getInt("rating_"+key,preferences.getInt("camera-rating_"+key,0));}
    void rate(String key,int stars){if(stars<0||stars>5)throw new IllegalArgumentException("Rating must be 0 to 5");preferences.edit().putInt("rating_"+key,stars).apply();}
    boolean localRating(String key){return preferences.contains("rating_"+key);}
    boolean knownRating(String key){return localRating(key)||preferences.contains("camera-rating_"+key);}
    void cameraRating(String key,int stars){if(stars<0||stars>5)return;SharedPreferences.Editor edit=preferences.edit().putInt("camera-rating_"+key,stars);if(localRating(key)&&rating(key)==stars)edit.remove("rating_"+key);edit.apply();}
    boolean saved(String key){return preferences.contains("saved_"+key);}
    String savedUri(String key){return preferences.getString("saved_"+key,"");}
    boolean savedTo(String key,Uri tree){return saved(key)&&tree.toString().equals(preferences.getString("tree_"+key,""));}
    void saved(String key,Uri uri,Uri tree,long bytes,String hash){preferences.edit().putString("saved_"+key,uri.toString()).putString("tree_"+key,tree==null?"":tree.toString()).putLong("bytes_"+key,bytes).putString("hash_"+key,hash).putBoolean("failed_"+key,false).commit();}
    void failed(String key){preferences.edit().putBoolean("failed_"+key,true).apply();}
    boolean failedBefore(String key){return preferences.getBoolean("failed_"+key,false);}
    void markSent(String key){preferences.edit().putLong("sent_"+key,System.currentTimeMillis()).apply();}
    boolean wasSent(String key){return preferences.contains("sent_"+key);}
    void forgetSaved(String key){preferences.edit().remove("saved_"+key).remove("tree_"+key).apply();}
}
