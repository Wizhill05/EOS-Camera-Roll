#include "libraw/libraw.h"
#include <iostream>
#include <memory>
#include <fstream>
int main(int argc,char **argv){
 if(argc<2)return 2;LibRaw raw;raw.imgdata.params.use_camera_wb=1;raw.imgdata.params.output_color=1;raw.imgdata.params.output_bps=8;raw.imgdata.params.user_qual=3;raw.imgdata.params.half_size=0;
 int result=raw.open_file(argv[1]);if(!result)result=raw.unpack();if(!result)result=raw.dcraw_process();if(result){std::cerr<<libraw_strerror(result)<<"\n";return 1;}
 if(raw.imgdata.other.iso_speed<=0||raw.imgdata.other.shutter<=0||raw.imgdata.other.aperture<=0||raw.imgdata.other.focal_len<=0)return 1;
 std::cout<<"Capture metadata: ISO "<<raw.imgdata.other.iso_speed<<", shutter "<<raw.imgdata.other.shutter<<", aperture "<<raw.imgdata.other.aperture<<", focal length "<<raw.imgdata.other.focal_len<<", lens "<<raw.imgdata.lens.Lens<<"\n";
 int error=0;auto image=std::unique_ptr<libraw_processed_image_t,void(*)(libraw_processed_image_t*)>(raw.dcraw_make_mem_image(&error),LibRaw::dcraw_clear_mem);
 if(!image||error||image->type!=LIBRAW_IMAGE_BITMAP||image->bits!=8||image->colors!=3||image->width<5900||image->height<3900)return 1;
 unsigned long long sum=0;for(unsigned i=0;i<image->data_size;i++)sum+=image->data[i];if(!sum)return 1;
 std::cout<<raw.imgdata.idata.make<<" "<<raw.imgdata.idata.model<<": "<<image->width<<" x "<<image->height<<", RGB "<<image->bits<<" bits, "<<image->data_size<<" rendered bytes, pixel sum "<<sum<<"\n";
 if(argc>2){std::ofstream out(argv[2],std::ios::binary);out<<"P6\n"<<image->width<<" "<<image->height<<"\n255\n";out.write(reinterpret_cast<char*>(image->data),image->data_size);}
 return 0;
}
