FFMPlayer
=======

A hybrid mediaplayer for android. It uses FFmpeg (has more filtering capabilities and more wider file support) or the android default audioplayer to play audio files. It is currently being used in [AmpX](https://play.google.com/store/apps/details?id=com.music.ampxnative&hl=en).

Features
--------

* Plays a lot more audio formats.
* More customizable audio filters.
* Audio Scrobbling.
* And many more.. 

Building
----------
This library uses android-ndk and ships with arm-v7 libraries by default, if you want to extend the player to support more architectures
you will have to build FFMpeg manually.

#### For Arm-v7

* Clone the repository and import the project in Android Studio
* Build APK for the project.
* An aar file will be generated with all the resources. Import this file in the project.

#### For other architectures
* Build FFMpeg for other architecture, and then place the output in app/src/main/jni/lib/<architecture>.
* Update Application.mk `APP_ABI` with comma separated values for each new architecture.
* Update module build.gradle abiFilter to include the new architecture.

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
mPlayer.useFilter("atempo", "0.8");
mp.release();
mp.setDataSource(<path to file2>, <file_id2>);
```