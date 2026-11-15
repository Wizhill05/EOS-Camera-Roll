#include <jni.h>
#include <android/bitmap.h>
#include <atomic>
#include <memory>
#include "libraw/libraw.h"

static std::atomic<long long> cancelledThrough{0};
static int progress(void *context, enum LibRaw_progress, int, int) {
    return *static_cast<long long *>(context) <= cancelledThrough.load() ? 1 : 0;
}
static void fail(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
extern "C" JNIEXPORT void JNICALL
Java_dev_aryan_cameralinklab_RawRenderer_cancelNative(JNIEnv *, jclass, jlong id) {
    long long prior=cancelledThrough.load();
    while(prior<id&&!cancelledThrough.compare_exchange_weak(prior,id)){}
}
extern "C" JNIEXPORT jobject JNICALL
Java_dev_aryan_cameralinklab_RawRenderer_renderNative(JNIEnv *env, jclass, jstring path, jlong job) {
    long long id=job;
    if(progress(&id,LIBRAW_PROGRESS_START,0,0)){fail(env,"RAW render cancelled");return nullptr;}
    const char *filename=env->GetStringUTFChars(path,nullptr);
    if(!filename)return nullptr;
    try {
        LibRaw raw;
        raw.set_progress_handler(progress,&id);
        raw.imgdata.rawparams.max_raw_memory_mb=512;
        raw.imgdata.params.use_camera_wb=1;
        raw.imgdata.params.output_color=1;
        raw.imgdata.params.output_bps=8;
        raw.imgdata.params.user_qual=3;
        raw.imgdata.params.half_size=0;
        int result=raw.open_file(filename);
        env->ReleaseStringUTFChars(path,filename);filename=nullptr;
        if(!result)result=raw.unpack();
        if(!result)result=raw.dcraw_process();
        if(result){fail(env,libraw_strerror(result));return nullptr;}
        int error=0;
        std::unique_ptr<libraw_processed_image_t,void(*)(libraw_processed_image_t*)> image(raw.dcraw_make_mem_image(&error),LibRaw::dcraw_clear_mem);
        if(!image||error){fail(env,error?libraw_strerror(error):"RAW render returned no image");return nullptr;}
        if(image->type!=LIBRAW_IMAGE_BITMAP||image->bits!=8||image->colors<3||!image->width||!image->height||
           static_cast<unsigned long long>(image->width)*image->height>64000000ULL||
           static_cast<unsigned long long>(image->width)*image->height*image->colors>image->data_size){fail(env,"Unsupported or oversized RAW output");return nullptr;}
        if(progress(&id,LIBRAW_PROGRESS_START,0,0)){fail(env,"RAW render cancelled");return nullptr;}
        jclass bitmapClass=env->FindClass("android/graphics/Bitmap");
        jclass configClass=env->FindClass("android/graphics/Bitmap$Config");
        jobject config=env->GetStaticObjectField(configClass,env->GetStaticFieldID(configClass,"ARGB_8888","Landroid/graphics/Bitmap$Config;"));
        jmethodID create=env->GetStaticMethodID(bitmapClass,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        jobject bitmap=env->CallStaticObjectMethod(bitmapClass,create,(jint)image->width,(jint)image->height,config);
        if(env->ExceptionCheck()||!bitmap)return nullptr;
        AndroidBitmapInfo info;void *pixels=nullptr;
        if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS||AndroidBitmap_lockPixels(env,bitmap,&pixels)!=ANDROID_BITMAP_RESULT_SUCCESS){fail(env,"Cannot allocate RAW bitmap");return nullptr;}
        for(unsigned row=0;row<image->height;row++){
            if(progress(&id,LIBRAW_PROGRESS_START,0,0)){AndroidBitmap_unlockPixels(env,bitmap);fail(env,"RAW render cancelled");return nullptr;}
            auto *target=static_cast<unsigned char *>(pixels)+row*info.stride;
            auto *source=image->data+row*image->width*image->colors;
            for(unsigned column=0;column<image->width;column++){
                target[column*4]=source[column*image->colors];target[column*4+1]=source[column*image->colors+1];
                target[column*4+2]=source[column*image->colors+2];target[column*4+3]=255;
            }
        }
        AndroidBitmap_unlockPixels(env,bitmap);return bitmap;
    } catch(const std::exception &error) {
        if(filename)env->ReleaseStringUTFChars(path,filename);
        fail(env,error.what());return nullptr;
    } catch(...) {
        if(filename)env->ReleaseStringUTFChars(path,filename);
        fail(env,"RAW decoder failed");return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_aryan_cameralinklab_RawRenderer_captureNative(JNIEnv *env,jclass,jstring path){
    const char *filename=env->GetStringUTFChars(path,nullptr);if(!filename)return nullptr;
    try{
        LibRaw raw;int result=raw.open_file(filename);env->ReleaseStringUTFChars(path,filename);filename=nullptr;
        if(result){fail(env,libraw_strerror(result));return nullptr;}
        jobjectArray values=env->NewObjectArray(8,env->FindClass("java/lang/String"),nullptr);if(!values)return nullptr;
        double numbers[]={raw.imgdata.other.shutter,raw.imgdata.other.aperture,raw.imgdata.other.iso_speed,raw.imgdata.other.focal_len};
        for(int i=0;i<4;i++){char value[64]={0};if(numbers[i]>0)snprintf(value,sizeof(value),"%.8g",numbers[i]);jstring text=env->NewStringUTF(value);env->SetObjectArrayElement(values,i,text);env->DeleteLocalRef(text);}
        const char *strings[]={raw.imgdata.lens.Lens,raw.imgdata.idata.model};for(int i=0;i<2;i++){jstring text=env->NewStringUTF(strings[i]);env->SetObjectArrayElement(values,4+i,text);env->DeleteLocalRef(text);}
        char width[32],height[32];snprintf(width,sizeof(width),"%u",raw.imgdata.sizes.width);snprintf(height,sizeof(height),"%u",raw.imgdata.sizes.height);
        jstring w=env->NewStringUTF(width),h=env->NewStringUTF(height);env->SetObjectArrayElement(values,6,w);env->SetObjectArrayElement(values,7,h);env->DeleteLocalRef(w);env->DeleteLocalRef(h);return values;
    }catch(...){if(filename)env->ReleaseStringUTFChars(path,filename);fail(env,"Could not read RAW capture metadata");return nullptr;}
}
