package dev.aryan.cameralinklab;

/** Reads only bounded EXIF headers. No original image decoding or camera mutation. */
final class ExifOrientation {
    static int read(byte[] bytes){
        if(bytes.length<8)return 0;
        if((bytes[0]&255)==255&&(bytes[1]&255)==216){
            int at=2;
            while(at+4<=bytes.length){
                if((bytes[at]&255)!=255)break;int marker=bytes[at+1]&255;at+=2;
                if(marker==218||marker==217)break;
                int length=((bytes[at]&255)<<8)|(bytes[at+1]&255);
                if(length<2||at+length>bytes.length)break;
                if(marker==225&&length>=16&&bytes[at+2]=='E'&&bytes[at+3]=='x'&&bytes[at+4]=='i'&&bytes[at+5]=='f'&&bytes[at+6]==0&&bytes[at+7]==0){int found=tiff(bytes,at+8,at+length);if(found>0)return found;}
                at+=length;
            }
        }
        int direct=tiff(bytes,0,bytes.length);if(direct>0)return direct;
        for(int i=4;i+12<bytes.length;i++)if(bytes[i]=='C'&&bytes[i+1]=='M'&&bytes[i+2]=='T'&&bytes[i+3]=='1'){
            long length=0;for(int j=i-4;j<i;j++)length=(length<<8)|(bytes[j]&255);
            if(length>=16){int found=tiff(bytes,i+4,(int)Math.min(bytes.length,(long)i-4+length));if(found>0)return found;}
        }
        return 0;
    }
    private static int tiff(byte[] b,int start,int end){
        if(start<0||end>b.length||start+8>end)return 0;
        boolean little=b[start]=='I'&&b[start+1]=='I';if(!little&&!(b[start]=='M'&&b[start+1]=='M'))return 0;
        if(u16(b,start+2,little)!=42)return 0;
        long offset=u32(b,start+4,little);if(offset<8||offset>end-start-2)return 0;
        int directory=start+(int)offset,count=u16(b,directory,little);
        if(count>1024||directory+2L+count*12L>end)return 0;
        for(int i=0;i<count;i++){int at=directory+2+i*12;if(u16(b,at,little)==274&&u16(b,at+2,little)==3&&u32(b,at+4,little)==1){int orientation=u16(b,at+8,little);return orientation>=1&&orientation<=8?orientation:0;}}
        return 0;
    }
    private static int u16(byte[] b,int at,boolean little){return little?(b[at]&255)|((b[at+1]&255)<<8):((b[at]&255)<<8)|(b[at+1]&255);}
    private static long u32(byte[] b,int at,boolean little){long result=0;for(int i=0;i<4;i++)result=(result<<8)|(b[at+(little?3-i:i)]&255);return result;}
    static int fromDegrees(int degrees){switch(degrees){case 0:return 1;case 90:return 6;case 180:return 3;case 270:return 8;default:return 0;}}
    static int effective(int embedded,int fallback,int width,int height){
        if(embedded>1)return embedded;
        // Canon may already rotate the thumbnail pixels while retaining the original's metadata.
        if((fallback==6||fallback==8)&&height>width)return 1;
        return fallback>0?fallback:embedded>0?embedded:1;
    }
}
