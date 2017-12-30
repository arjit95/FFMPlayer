FFMPlayer
=======

A hybrid mediaplayer for android. It uses FFmpeg (has more filtering capabilities and more wider file support) or the android default audioplayer to play audio files.

Features
--------

* Plays a lot more audio formats.
* More customizable audio filters.
* Audio Scrobbling.
* And many more.. 

Building
----------

#### For various architectures
* Build FFMpeg for other architecture, and then place the output in app/src/main/jni/lib/<architecture>.
* Update Application.mk `APP_ABI` with comma separated values for each new architecture.
* Update module build.gradle abiFilter to include the new architecture.

Note: Make sure all the below *.so files are present in lib folder
libavcodec.so, libavfilter.so, libavformat.so, libavutil.so, libswscale.so, libswresample.so

Usage
--------
```java
//Set 2nd arg to true if you want to use FFMpeg engine or false for android default.
MediaPlayer mp = new MediaPlayer(mContext, true)
mp.setOnCompleteListener(new MediaPlayer.OnCompleteListener() {
        @Override
        public void onComplete() {
            //next();
        }
        @Override
        public void onPrepared() {
            //play();
        }
});
//2nd arg is used to get metadata of file from android mediastore
mp.setDataSource(<path to file>, <file_id>)
mp.play();
//Filter usage more info at https://www.ffmpeg.org/ffmpeg-filters.html
mp.useFilter("atempo", "0.8");
mp.release();
```