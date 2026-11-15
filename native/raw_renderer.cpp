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
