package dev.aryan.cameralinklab;

import java.util.*;

/** Pair only same-folder, same-stem Canon RAW and JPEG files. */
final class MediaPairs {
    final Map<MediaItem,MediaItem> other=new HashMap<>();
    MediaPairs(List<MediaItem> files){
        Map<String,List<MediaItem>> groups=new LinkedHashMap<>();
        for(MediaItem file:files)if(file.raw()||jpeg(file))groups.computeIfAbsent(file.folder+"/"+stem(file),ignored->new ArrayList<>()).add(file);
        for(List<MediaItem> group:groups.values()){
            List<MediaItem> raws=new ArrayList<>(),jpegs=new ArrayList<>();for(MediaItem file:group)if(file.raw())raws.add(file);else jpegs.add(file);
            if(raws.size()==1&&jpegs.size()==1){other.put(raws.get(0),jpegs.get(0));other.put(jpegs.get(0),raws.get(0));}
        }
    }
    static boolean jpeg(MediaItem file){return file.extension.equals("JPG")||file.extension.equals("JPEG");}
    static String stem(MediaItem file){return file.name.substring(0,file.name.lastIndexOf('.')).toUpperCase(Locale.ROOT);}
    MediaItem thumbnail(MediaItem file){MediaItem paired=other.get(file);return file.raw()&&paired!=null?paired:file;}
    List<MediaItem> representatives(List<MediaItem> files,int format){
        List<MediaItem> result=new ArrayList<>();for(MediaItem file:files){
            if(format==1&&!file.raw()||format==2&&!jpeg(file)||format==3&&!other.containsKey(file)||format==4&&other.containsKey(file))continue;
            if((format==0||format==3)&&file.raw()&&other.containsKey(file))continue;result.add(file);
        }return result;
    }
    List<MediaItem> expand(Collection<MediaItem> files){Set<MediaItem> result=new LinkedHashSet<>();for(MediaItem file:files){result.add(file);MediaItem paired=other.get(file);if(paired!=null)result.add(paired);}return new ArrayList<>(result);}
}
