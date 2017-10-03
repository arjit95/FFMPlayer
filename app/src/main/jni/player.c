#include <android/log.h>
#include <jni.h>
#include <string.h>
#include <math.h>
#include <stdlib.h>

#include "libavcodec/avcodec.h"
#include "libavformat/avformat.h"
#include "libavutil/samplefmt.h"
#include "libavfilter/avfilter.h"
#include "libavfilter/buffersink.h"
#include "libavfilter/buffersrc.h"
#include "libswresample/swresample.h"
#include "libavutil/opt.h"
#include "libavutil/error.h"

#define LOG_TAG "AMPX"
#define MAX_AUDIO_FRAME_SIZE 192000
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct State {
  double audio_clock;
  int64_t duration;
  enum AVSampleFormat currentFormat;

  AVFormatContext* formatContext;
  AVCodecContext* codecContext;
  AVPacket readingPacket;
  jobject audio_track;
};

struct filter{
	AVFilterGraph *filter_graph;
	AVFilterContext *src, *sink;
};

int data_size,
    rate_backup,
    first_init       = -1,
    audio_index      = -1,
    filter_process   = -1,
    seek             =  0,
    stop             =  0,
    error            =  0,
    started          =  0,
    reduction        =  0,
    init_gain        =  0,
    set_clear_flag   =  0,
    new_fitlers      =  0,
    use_filter       =  1,
    channels         =  1,
    sdk_level        =  16,
    rate             =  8000;

float max         =  0.0f,
      sample_max  =  0.0f,
      sample_dbFS = -60.0f,
      gain_change;

static struct State s;
static struct filter filters;
static jclass audio_track_cls;

uint8_t *out_buffer_pcm16;
jbyte *out_buffer;
int64_t msec;

//Checks if we have to clear the filters if true
char *filter_options;

int init_filters(const char *filters_descr);
void resample_audio(AVFrame *frames,uint8_t **samples);

/*
 * Initializing audio engine to be used
 */
enum AVSampleFormat out_format=AV_SAMPLE_FMT_S16;
void Java_me_arjit_ffmplayer_MediaPlayer_setSamplingRate(JNIEnv * env, jclass clazz,jint sampling_rate) {
	rate        = sampling_rate;
	rate_backup = sampling_rate;
}

static void avlog_cb(void *ab, int level, const char * szFmt, va_list varg) {
	if (level == AV_LOG_ERROR) {
      LOGE(szFmt,varg);
  }
}

void Java_me_arjit_ffmplayer_MediaPlayer_createEngine(JNIEnv * env, jclass clazz,jint sdk) {
  av_register_all();
  avfilter_register_all();

  jclass track_cls = (*env)->FindClass(env,"android/media/AudioTrack");
  audio_track_cls  = (jclass)(*env)->NewGlobalRef(env,track_cls);
  sdk_level         = sdk;
  if (track_cls == NULL) {
  	   LOGE("Cannot Find AudioTrack Class");
  }
  (*env)->DeleteLocalRef(env,track_cls);
  jbyte *tmp_buffer = (*env)->NewByteArray(env,MAX_AUDIO_FRAME_SIZE);
  out_buffer        = (jbyte *)(*env)->NewGlobalRef(env,tmp_buffer);
  (*env)->DeleteLocalRef(env,tmp_buffer);
  av_log_set_callback(avlog_cb);
  av_log_set_level(AV_LOG_ERROR);
}

/* check that a given sample format is supported by the encoder */
/*
 * Audio decoding.
 */
int64_t getDuration() {
    if (s.formatContext && (s.formatContext->duration != AV_NOPTS_VALUE)) {
          s.duration = (s.formatContext->duration / AV_TIME_BASE) * 1000;
    } else {
          s.duration = 0;
    }
    return s.duration;
}
/*
* Sets valid sample rate and channel from audio file
*/
void setValidStreams() {
	LOGI("Max Supported Audio Sampling Rate: %d",rate_backup);
	LOGI("Original Audio Data:Channels %d, Sample Rate %d, Audio Format %s",
  			s.codecContext->channels,
  			s.codecContext->sample_rate,
  			av_get_sample_fmt_name(s.codecContext->sample_fmt));
	if (s.codecContext->channels >= 2) {
		  channels = 2;
  }
	if (s.codecContext->sample_rate < rate) {
		  rate = s.codecContext->sample_rate;
  } else {
		  rate = rate_backup;
  }
	LOGI("Modified Audio Data:Channels %d, Sample Rate %d, Audio Format %s",
  			channels,
  			rate,
  			av_get_sample_fmt_name(out_format));
}

jintArray Java_me_arjit_ffmplayer_MediaPlayer_getFileInfo(JNIEnv * env, jobject obj,jstring file) {
    jboolean isfilenameCopy;
    error = 1;
    const char *filename = (*env)->GetStringUTFChars(env, file, &isfilenameCopy);
    int cArray[3];
    jsize len        = sizeof(cArray);
    jintArray jArray = (*env)->NewIntArray(env, len);
    started          = 1;
    channels         = 1;
    rate             = rate_backup;

    AVCodec *dec;
    if (avformat_open_input(&s.formatContext,filename, NULL, NULL) != 0) {
        LOGE("Error opening the file %s",filename);
        return NULL;
    }
    if (avformat_find_stream_info(s.formatContext, NULL) < 0) {
        avformat_close_input(&s.formatContext);
        LOGE("Error finding the stream info");
        return NULL;
    }

    // Find the audio stream
    audio_index = av_find_best_stream(s.formatContext, AVMEDIA_TYPE_AUDIO, -1, -1, &dec, 0);
    if (audio_index < 0) {
         avformat_close_input(&s.formatContext);
         LOGE("Could not find any audio stream in the file");
         return NULL;
    }

    s.audio_clock=0;
    s.codecContext = s.formatContext->streams[audio_index]->codec;
 	  
    if (avcodec_open2(s.codecContext,dec, NULL) != 0){
	    LOGE("Couldn't open the context with the decoder");
	    avformat_close_input(&s.formatContext);
	    return NULL;
   	}

   	s.codecContext->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
    if (s.codecContext->sample_fmt == AV_SAMPLE_FMT_S16P) {
   		s.codecContext->request_sample_fmt = AV_SAMPLE_FMT_S16;
   	}
   	if (!s.codecContext->channel_layout) {
    	s.codecContext->channel_layout = av_get_default_channel_layout(s.codecContext->channels);
    }
   	
    setValidStreams();
    cArray[0] = channels;
    cArray[1] = rate;
    
    double time_base     = (double)s.formatContext->streams[audio_index]->time_base.num / (double)s.formatContext->streams[audio_index]->time_base.den;
    int duration         = (int)(s.formatContext->streams[audio_index]->duration * time_base);
    s.readingPacket.size = 0;
    s.audio_clock        = 0.0f;
    cArray[2]            = duration;
    seek                 = 0;
    msec                 = 0;
    
    (*env)->SetIntArrayRegion(env, jArray, 0, len, cArray);
    (*env)->ReleaseStringUTFChars(env,file,filename);
    jmethodID min_buff_size_id = (*env)->GetStaticMethodID(
                                         env,
                                         audio_track_cls,
                                        "getMinBufferSize",
                                        "(III)I");
    int channelConfig = channels == 1 ? 4 : 12,
        format        = 2,
        buffer_size   = (*env)->CallStaticIntMethod(env,audio_track_cls,min_buff_size_id,
                                                    rate,
                                                    channelConfig,   /*CHANNEL_CONFIGURATION_MONO*/
                                                    format);         /*ENCODING_PCM_16BIT*/
    jmethodID constructor_id = (*env)->GetMethodID(env,audio_track_cls, "<init>",
                                                  "(IIIIII)V");
    jobject audio_track      = (*env)->NewObject(env,audio_track_cls,
                                                 constructor_id,
                                                 3,               /*AudioManager.STREAM_MUSIC*/
                                                 rate,            /*sampleRateInHz*/
                                                 channelConfig,   /*CHANNEL_CONFIGURATION_MONO*/
                                                 format,          /*ENCODING_PCM_16BIT*/
                                                 buffer_size*4,   /*bufferSizeInBytes*/
                                                 1);              /*AudioTrack.MODE_STREAM*/

    s.audio_track = (jobject)(*env)->NewGlobalRef(env,audio_track);
    (*env)->DeleteLocalRef(env,audio_track);
    filter_process = -1;
    error           = 0;
    return jArray;
}
double Java_me_arjit_ffmplayer_MediaPlayer_getBitRate(JNIEnv* env, jobject obj) {
    return (double)s.codecContext->bit_rate;
}

void Java_me_arjit_ffmplayer_MediaPlayer_seekTo(JNIEnv* env, jobject obj,jdouble sec) {
    msec = sec;
    seek = 1;
}

jdouble Java_me_arjit_ffmplayer_MediaPlayer_getCurrentPosition(JNIEnv* env, jobject obj){
    return s.audio_clock;
}

int init_filters(const char *filters_descr) {
    first_init=0;
    char args[512];
    int ret = 0;

    AVFilter *abuffersrc   = avfilter_get_by_name("abuffer");
    AVFilter *abuffersink  = avfilter_get_by_name("abuffersink");
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs  = avfilter_inout_alloc();

    enum AVSampleFormat out_sample_fmts[] = { out_format, -1 };
    static const int64_t out_channel_layouts[] = { AV_CH_LAYOUT_MONO,AV_CH_LAYOUT_STEREO, -1 };
    int out_sample_rates[] = {rate,-1};
    const AVFilterLink *outlink;
    AVRational time_base = s.formatContext->streams[audio_index]->time_base;
    filters.filter_graph = avfilter_graph_alloc();
    if (!outputs || !inputs || !filters.filter_graph) {
        ret = AVERROR(ENOMEM);
        goto end;
    }
    /* buffer audio source: the decoded frames from the decoder will be inserted here. */
    if (!s.codecContext->channel_layout) {
        s.codecContext->channel_layout = av_get_default_channel_layout(s.codecContext->channels);
    }
    snprintf(args, sizeof(args),
           "time_base=%d/%d:sample_rate=%d:sample_fmt=%s:channel_layout=0x%"PRIx64,
            time_base.num, time_base.den, s.codecContext->sample_rate,
            av_get_sample_fmt_name(s.codecContext->sample_fmt), s.codecContext->channel_layout);
    ret = avfilter_graph_create_filter(&filters.src, abuffersrc, "in",
                                      args, NULL, filters.filter_graph);
    if (ret < 0) {
        LOGE("Cannot create audio buffer source\n");
        goto end;
    }

    /* buffer audio sink: to terminate the filter chain. */
    ret = avfilter_graph_create_filter(&filters.sink, abuffersink, "out",
                                      NULL, NULL, filters.filter_graph);
    if (ret < 0) {
        LOGE("Cannot create audio buffer sink\n");
        goto end;
    }

    ret = av_opt_set_int_list(filters.sink, "sample_fmts", out_sample_fmts, -1,
                             AV_OPT_SEARCH_CHILDREN);
    if (ret < 0) {
        LOGE("Cannot set output sample format\n");
        goto end;
    }

    ret = av_opt_set_int_list(filters.sink, "channel_layouts", out_channel_layouts, -1,
                             AV_OPT_SEARCH_CHILDREN);
    if (ret < 0) {
        LOGE("Cannot set output channel layout\n");
        goto end;
    }

    ret = av_opt_set_int_list(filters.sink, "sample_rates", out_sample_rates, -1,
                             AV_OPT_SEARCH_CHILDREN);
    if (ret < 0) {
        LOGE("Cannot set output sample rate\n");
        goto end;
    }

    /*
    * Set the endpoints for the filter graph. The filter_graph will
    * be linked to the graph described by filters_descr.
    */

    /*
    * The buffer source output must be connected to the input pad of
    * the first filter described by filters_descr; since the first
    * filter input label is not specified, it is set to "in" by
    * default.
    */
    outputs->name       = av_strdup("in");
    outputs->filter_ctx = filters.src;
    outputs->pad_idx    = 0;
    outputs->next       = NULL;

    /*
    * The buffer sink input must be connected to the output pad of
    * the last filter described by filters_descr; since the last
    * filter output label is not specified, it is set to "out" by
    * default.
    */
    inputs->name       = av_strdup("out");
    inputs->filter_ctx = filters.sink;
    inputs->pad_idx    = 0;
    inputs->next       = NULL;

    /* 
    * Print summary of the sink buffer
    * Note: args buffer is reused to store channel layout string 
    */

    if ((ret = avfilter_graph_parse_ptr(filters.filter_graph, filters_descr,
                                       &inputs, &outputs, NULL)) < 0) {
       goto end;
    }
    if ((ret = avfilter_graph_config(filters.filter_graph, NULL)) < 0) {
        LOGE("Cannot config graph");
        goto end;
    }

    outlink = filters.sink->inputs[0];
    av_get_channel_layout_string(args, sizeof(args), -1, outlink->channel_layout);
    ret=0;

    end:
        avfilter_inout_free(&inputs);
        avfilter_inout_free(&outputs);
    return ret;
}

void Java_me_arjit_ffmplayer_MediaPlayer_enableFilter(JNIEnv* env, jobject obj,jint value) {
    use_filter = value;
}

/* 
* Create the aformat filter;
* it ensures that the output is of the format we want. 
*/
int Java_me_arjit_ffmplayer_MediaPlayer_setFinalFilter(JNIEnv* env, jobject obj,jstring filter) {
    jboolean isFileNameCopy;
    const char *filterString = (*env)->GetStringUTFChars(env,filter,&isFileNameCopy);
    filter_options = malloc(sizeof(char *) * strlen(filterString));
    strcpy(filter_options,filterString);
    (*env)->ReleaseStringUTFChars(env,filter,filterString);
    new_fitlers = 1;
    return 0;
}

void clearFilters() {
    set_clear_flag    = 1;
    filter_process = -1;
}

void Java_me_arjit_ffmplayer_MediaPlayer_deleteFilter(JNIEnv* env, jobject obj) {
    clearFilters();
}

void Java_me_arjit_ffmplayer_MediaPlayer_refreshFilters(JNIEnv* env, jobject obj) {
    init_gain = -2;
}

void Java_me_arjit_ffmplayer_MediaPlayer_playTrack(JNIEnv* env, jobject obj) {
    if (s.audio_track == NULL) {
         return;
    }
    stop = 0;
    jmethodID play = (*env)->GetMethodID(env,audio_track_cls,"play","()V");
    (*env)->CallVoidMethod(env,s.audio_track,play);
}

void Java_me_arjit_ffmplayer_MediaPlayer_pauseTrack(JNIEnv* env, jobject obj) {
    if (s.audio_track == NULL) {
         return;
    }
    stop = 1;
    jmethodID pause = (*env)->GetMethodID(env,audio_track_cls,"pause","()V");
    (*env)->CallVoidMethod(env,s.audio_track,pause);
}

void Java_me_arjit_ffmplayer_MediaPlayer_attachAuxEffect(JNIEnv* env, jobject obj,jint effectid) {
    if (s.audio_track == NULL) {
         return;
    }
    jmethodID aux = (*env)->GetMethodID(env,audio_track_cls,"attachAuxEffect","(I)I");
    (*env)->CallIntMethod(env,s.audio_track,aux,effectid);
}

void Java_me_arjit_ffmplayer_MediaPlayer_setAuxEffectSendLevel(JNIEnv* env, jobject obj,jfloat level) {
  	 if (s.audio_track == NULL) {
  	 		 return;
     }
     jmethodID aux=(*env)->GetMethodID(env,audio_track_cls,"setAuxEffectSendLevel","(F)I");
     (*env)->CallIntMethod(env,s.audio_track,aux,level);
}

void Java_me_arjit_ffmplayer_MediaPlayer_stopTrack(JNIEnv* env, jobject obj) {
    if (s.audio_track == NULL) {
    		 return;
    }
    stop = 1;
    jmethodID mStop=(*env)->GetMethodID(env,audio_track_cls,"stop","()V");
    (*env)->CallVoidMethod(env,s.audio_track,mStop);
}

int Java_me_arjit_ffmplayer_MediaPlayer_getTrackPlaybackHeadPosition(JNIEnv* env, jobject obj) {
    if (s.audio_track == NULL) {
    	 return -1;
    }
    jmethodID getHead=(*env)->GetMethodID(env,audio_track_cls,"getPlaybackHeadPosition","()I");
    return (*env)->CallIntMethod(env,s.audio_track,getHead);
}

int Java_me_arjit_ffmplayer_MediaPlayer_getTrackSessionId(JNIEnv* env, jobject obj) {
    if(s.audio_track==NULL) {
        return -1;
    }
    jmethodID getSession=(*env)->GetMethodID(env,audio_track_cls,"getAudioSessionId","()I");
    return (*env)->CallIntMethod(env,s.audio_track,getSession);
}

int Java_me_arjit_ffmplayer_MediaPlayer_getPlayState(JNIEnv* env, jobject obj){
    if(s.audio_track==NULL) {
        return 0;
    }
    jmethodID getState=(*env)->GetMethodID(env,audio_track_cls,"getPlayState","()I");
    return (*env)->CallIntMethod(env,s.audio_track,getState);
}

void Java_me_arjit_ffmplayer_MediaPlayer_flushTrack(JNIEnv* env, jobject obj) {
    if(s.audio_track==NULL) {
          return;
    }
    jmethodID flush=(*env)->GetMethodID(env,audio_track_cls,"flush","()V");
    (*env)->CallVoidMethod(env,s.audio_track,flush);
}

void Java_me_arjit_ffmplayer_MediaPlayer_setVolumeTrack(JNIEnv* env, jobject obj,jint sdk,jfloat gain) {
    if (s.audio_track==NULL) {
         return;
    }
    if (sdk >= 21) {
         jmethodID setStereoVolume = (*env)->GetMethodID(env,audio_track_cls,"setVolume","(F)I");
         (*env)->CallIntMethod(env,s.audio_track,setStereoVolume,gain);
    } else {
         jmethodID setStereoVolume = (*env)->GetMethodID(env,audio_track_cls,"setStereoVolume","(FF)I");
         (*env)->CallIntMethod(env,s.audio_track,setStereoVolume,gain,gain);
    }
}

jboolean Java_me_arjit_ffmplayer_MediaPlayer_decodePacket(JNIEnv* env, jobject obj) {
    int ret=0;
    jmethodID write = (*env)->GetMethodID(env,audio_track_cls,"write","([BII)I");
    while (1 && stop==0) {
            if (s.readingPacket.size <= 0) {
                  ret= av_read_frame(s.formatContext, &s.readingPacket);
                  if (ret < 0){
                  	 av_packet_unref(&s.readingPacket);
                  	 return JNI_TRUE;
                  }
            }

            AVFrame *frame = av_frame_alloc();
            int got_frame = 0;
            int err;
            AVPacket *pkt = &s.readingPacket;
            if (pkt->stream_index == audio_index) {
                // Audio packets can have multiple audio frames in a single packet
                // Try to decode the packet into a frame
                // Some frames rely on multiple packets, so we have to make sure the frame is finished before
                // we can use it
                int gotFrame = 0;
                int result = avcodec_decode_audio4(s.codecContext, frame, &gotFrame, &s.readingPacket);
                if (result >= 0 && gotFrame) {
                  	if (new_fitlers==1 && use_filter>0){
              			      avfilter_graph_free(&filters.filter_graph);
                		      if (init_filters(filter_options)==0) {
                		           filter_process=1;
                		      }
                		      else {
                		    	  filter_process=-1;
                          }
                		      free(filter_options);
                		      new_fitlers=0;
                  	}

                    pkt->size -= result;
                    pkt->data += result;
                    /* if a frame has been decoded, and we are adding filters then output it */
                    int copied=0;
                    out_buffer_pcm16=malloc(MAX_AUDIO_FRAME_SIZE);
                    if(filter_process >= 0) {
                    	 err = av_buffersrc_add_frame(filters.src, frame);
                    	 AVFrame *filt_frame=av_frame_alloc();
                       while ((err = av_buffersink_get_frame(filters.sink, filt_frame)) >= 0) {
                         /* 
                          * now do something with our filtered frame
                          */
                      	  data_size = av_samples_get_buffer_size(NULL, channels,
                      	   	                          filt_frame->nb_samples,
                      	         	                  out_format, 1);

                          memcpy(out_buffer_pcm16+copied,filt_frame->extended_data[0],data_size);
                          copied+=data_size;
                          av_frame_unref(filt_frame);
                        }
                        av_frame_free(&filt_frame);
                        av_freep(&filt_frame);
              	    }
                    else {
                	     /* now do something with our filtered frame */
                  		 if(s.codecContext->sample_fmt!=out_format || s.codecContext->channels>channels || s.codecContext->sample_rate>rate) {
                  			  resample_audio(frame,&out_buffer_pcm16);
                  		 } else {
                          data_size = av_samples_get_buffer_size(NULL, s.codecContext->channels,
                                                                 frame->nb_samples,
                                         	                       s.codecContext->sample_fmt, 1);
                          memcpy(out_buffer_pcm16,frame->extended_data[0],data_size);
                       }
                       copied = data_size;
                  	}
                    (*env)->SetByteArrayRegion(env,out_buffer, 0,copied,(jbyte *)out_buffer_pcm16);
                    (*env)->CallIntMethod(env,s.audio_track,write,out_buffer,0,copied);
                    (*env)->ReleaseByteArrayElements(env,out_buffer,out_buffer_pcm16,JNI_COMMIT);
                    free(out_buffer_pcm16);
                    av_frame_unref(frame);
                } else {
            	  	pkt->size = 0;
                    pkt->data = NULL;
                }
                int n = 2 * s.codecContext->channels;
                s.audio_clock += (double)data_size /
            					 (double)(n * s.codecContext->sample_rate);
                /* if update, update the audio clock w/pts */
                if(pkt->pts != AV_NOPTS_VALUE) {
                	s.audio_clock = av_q2d(s.formatContext->streams[audio_index]->time_base) * pkt->pts;
                }
            }
            else {
                pkt->size=0;
                pkt->data=NULL;
            }

            if (seek == 1) {
              	  int64_t seek_target = msec * 1000;
              	  int64_t seek_min = msec > 0 ? seek_target - msec + 2: INT64_MIN;
              	  int64_t seek_max = msec < 0 ? seek_target - msec - 2: INT64_MAX;
                  int ret = avformat_seek_file(s.formatContext, -1, seek_min, seek_target, seek_max, AVSEEK_FLAG_FRAME);
              	  if (ret < 0) {
              	      LOGE("%s: error while seeking %lld\n Return Code: %d\n", s.formatContext->filename, msec, ret);
                  } else {
              		   avcodec_flush_buffers(s.codecContext);
          	      	   s.audio_clock = msec / 1000;
                  }
                  seek = 0;
            }
             // You *must* call av_free_packet() after each call to av_read_frame() or else you'll leak memory
            av_frame_free(&frame);
            av_freep(&frame);
            if(pkt->size <= 0 || stop != 0) {
                av_packet_unref(&s.readingPacket);
            }
            av_freep(pkt);
    }
    return JNI_FALSE;
}

void Java_me_arjit_ffmplayer_MediaPlayer_destroy(JNIEnv* env, jobject obj) {
    if (error==1 || s.audio_track==NULL) {
          return;
    }

    avcodec_close(s.codecContext);
    avformat_close_input(&s.formatContext);
    jmethodID release = (*env)->GetMethodID(env,audio_track_cls,"release","()V");

    (*env)->CallVoidMethod(env,s.audio_track,release);
    (*env)->DeleteGlobalRef(env,s.audio_track);
    s.audio_track=NULL;
}
void resample_audio(AVFrame *frames,uint8_t **samples) {
    int64_t src_ch_layout = av_get_default_channel_layout(frames->channels), dst_ch_layout=av_get_default_channel_layout(channels);
    int src_rate = frames->sample_rate,dst_rate=rate;
    uint8_t **src_data = frames->extended_data, **dst_data = NULL;
    int src_nb_channels = 0, dst_nb_channels = 0;
    int src_linesize, dst_linesize;
    int src_nb_sample,dst_nb_samples, max_dst_nb_samples;
    int dst_bufsize,ret;
    SwrContext *swr_ctx;
	  enum AVSampleFormat src_sample_fmt = s.codecContext->sample_fmt, dst_sample_fmt = out_format;
	  src_nb_sample = frames->nb_samples;
    src_linesize = (int) frames->linesize;
    src_data = frames->extended_data;
    if (frames->channel_layout == 0) {
    	frames->channel_layout = av_get_default_channel_layout(frames->channels);
    }

    /* create resampler context */
    swr_ctx = swr_alloc();
    if (!swr_ctx) {
    	//fprintf(stderr, "Could not allocate resampler context\n");
        //ret = AVERROR(ENOMEM);
        //goto end;
    }

    av_opt_set_int(swr_ctx, "in_channel_layout", src_ch_layout, 0);
    av_opt_set_int(swr_ctx, "out_channel_layout", dst_ch_layout,  0);
    av_opt_set_int(swr_ctx, "in_sample_rate", src_rate, 0);
    av_opt_set_int(swr_ctx, "out_sample_rate", dst_rate, 0);
    av_opt_set_sample_fmt(swr_ctx, "in_sample_fmt", src_sample_fmt, 0);
    av_opt_set_sample_fmt(swr_ctx, "out_sample_fmt", dst_sample_fmt,  0);

    /* initialize the resampling context */
    if ((ret = swr_init(swr_ctx)) < 0) {
        LOGE("Failed to initialize the resampling context");
        //goto end;
    }

    /* allocate source and destination samples buffers */
    src_nb_channels = av_get_channel_layout_nb_channels(src_ch_layout);
    ret = av_samples_alloc_array_and_samples(&src_data, &src_linesize, src_nb_channels,
		                                        src_nb_sample, src_sample_fmt, 0);
    if (ret < 0) {
    	LOGE("Could not allocate source samples");
    	//goto end;
    }

    /* compute the number of converted samples: buffering is avoided
     * ensuring that the output buffer will contain at least all the
     * converted input samples */
    max_dst_nb_samples = dst_nb_samples =
  	av_rescale_rnd(src_nb_sample, dst_rate, src_rate, AV_ROUND_UP);

    /* buffer is going to be directly written to a rawaudio file, no alignment */
    dst_nb_channels = av_get_channel_layout_nb_channels(dst_ch_layout);
    ret = av_samples_alloc_array_and_samples(&dst_data, &dst_linesize, dst_nb_channels,
    		                                     dst_nb_samples, dst_sample_fmt, 0);
    if (ret < 0) {
    	LOGE("Could not allocate destination samples");
    	//goto end;
    }

    /* compute destination number of samples */
    dst_nb_samples = av_rescale_rnd(swr_get_delay(swr_ctx, src_rate) +
                                    src_nb_sample, dst_rate, src_rate, AV_ROUND_UP);

    /* convert to destination format */
    ret = swr_convert(swr_ctx, dst_data, dst_nb_samples, (const uint8_t **)frames->extended_data, src_nb_sample);
    if (ret < 0) {
    	LOGE("Error while converting");
      //goto end;
    }
    dst_bufsize = av_samples_get_buffer_size(&dst_linesize, dst_nb_channels,
                                             ret, dst_sample_fmt, 1);
    if (dst_bufsize < 0) {
        fprintf(stderr, "Could not get sample buffer size\n");
        //goto end;
    }
    memcpy(*samples,dst_data[0], dst_bufsize);
    data_size = dst_bufsize;
    if (src_data) {
        av_freep(&src_data[0]);
		}
		av_freep(&src_data);
		if (dst_data) {
  			av_freep(&dst_data[0]);
		}
		av_freep(&dst_data);
		swr_free(&swr_ctx);
}
