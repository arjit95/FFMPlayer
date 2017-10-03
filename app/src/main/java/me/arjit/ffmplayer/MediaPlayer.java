package me.arjit.ffmplayer;


import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.WeakHashMap;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.exceptions.CannotReadException;
import org.jaudiotagger.audio.exceptions.CannotWriteException;
import org.jaudiotagger.audio.exceptions.InvalidAudioFrameException;
import org.jaudiotagger.audio.exceptions.ReadOnlyFileException;
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.TagException;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

public class MediaPlayer implements android.media.MediaPlayer.OnCompletionListener,
									android.media.MediaPlayer.OnPreparedListener,
									android.media.MediaPlayer.OnErrorListener{
		
	private native void createEngine(int sdk);
	private native boolean decodePacket();
	private native int[] getFileInfo(String file);
	private native void destroy();
	private native void seekTo(double msec);
	private native double getCurrentPosition();
	private native String getPresetByName(int preset);
	private native int setInitialFilter();
	private native int setFinalFilter(String filter);
	private native void deleteFilter();
	private native void enableFilter(int value);
	private native double getBitRate();
	private native void playTrack();
	private native void pauseTrack();
	private native void stopTrack();
	private native int  getTrackPlaybackHeadPosition();
	private native int getTrackSessionId();
	private native void attachAuxEffect(int effectid);
	private native void setAuxEffectSendLevel(float level);
	private native int getPlayState();
	private native void flushTrack();
	private native void setSamplingRate(int rate);
	private native void setVolumeTrack(int sdk,float gain);
	private int fileData[] = new int[3];
	private android.media.MediaPlayer mp;
	private String availableFilters[] = {"bass", "equalizer", "compand", "earwax", "flanger",
										 "treble", "bandpass","lowpass","highpass","aecho",
										 "chorus","volume","atempo","extrastereo"};
	private ArrayList<WeakHashMap<String,String>> filterOptions;
	private boolean filtering = false, nativeDecoder = true, released = true;
	private int duration;
	private Context app;
	private int compressorLevel=0;
	private String currentPlayer;
	String title,artist,album;
	private boolean bassFX=false,playing=false,extraCompand=false;
	private Thread runner;
	private MetaTags meta=null;

	double bitRate=0.0f;
	float gain=0.0f,extraGain=0.0f;
	private WeakReference<MetaTags> weakMeta=new WeakReference<MetaTags>(meta);
	public static final int SCROBBLE_DISABLED=0;
	public static final int SCROBBLE_DROID=1;
	public static final int SCROBBLE_SIMPLE_LASTFM_SCROBBLE=2;
	protected final int START=0;
	protected final int RESUME=1;
	protected final int PAUSE=2;
	protected final int COMPLETE=3;
	protected  boolean error=false;
	long trackId=0;
	int scrobble=0,compressor=0;
	public static final String PLAYER_STARTED="com.music.ampxnative.ffmp.started";
	public static final String PLAYER_PAUSED="com.music.ampxnative.ffmp.paused";
	public static final String PLAYER_RELEASED="com.music.ampxnative.ffmp.released";
	protected class PlayBack implements Runnable{

		@Override
		public void run() {
			// TODO Auto-generated method stub
			boolean r=decodePacket();
			if(r)
			   nextSignal();
		 return;
		}
	}
	private PlayBack pb=new PlayBack();
	private class MetaTags{
		private AudioFile audio;
		private Tag tag;
		private boolean audioValid=true;
		private boolean audioChanged=false;
		public void setAudio(String audio) {
			try {
				this.audio =AudioFileIO.read(new File(audio));
			} catch (CannotReadException | IOException | TagException
					| ReadOnlyFileException | InvalidAudioFrameException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				audioValid=false;
			}
			if(audioValid) {
				setTag();
			}
		}

		private void setTag() {
			 tag = audio.getTag();
		}

		public String getMeta(String key){
			 if(audioValid && tag!=null) {
				 return tag.getFirst(FieldKey.valueOf(key.toUpperCase()));
			 } else {
				 return "";
			 }
		}

		public void setMeta(String key,String val) {
			if(audioValid){
				try {
				 if(key.equals("") || val.equals(""))
					 return;
					tag.setField(FieldKey.valueOf(key.toUpperCase()),val);
					audioChanged=true;
				} catch (NullPointerException | KeyNotFoundException | FieldDataInvalidException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		public void release() {
			if (audioChanged) {
				try {
					audio.commit();
				} catch (CannotWriteException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}
	public void extraCompand(boolean state){
		extraCompand=state;
	}
	public int getValidSampleRates() {
		int rate[]= new int[] {8000, 11025, 16000, 22050, 44100,48000};
		for (int i=0;i<rate.length;i++) {  // add the rates you wish to check against
			int bufferSize = AudioTrack.getMinBufferSize(rate[i], AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
			if (bufferSize <= 0) {
				// buffer size is valid, Sample rate supported
			   if(rate[i]==8000)
				   return 8000;
			   return rate[i-1];
			}
		}
		return 48000;
	}
	public void sendBroadcast(String action){

	}
	public void setEffectSendLevel(float level){
		if(getNativeDecoder()) {
			setAuxEffectSendLevel(level);
		} else{
			if(mp!=null) {
				mp.setAuxEffectSendLevel(level);
			}
		}
	}
	public void attachEffect(int id){
		if(getNativeDecoder()) {
			attachAuxEffect(id);
		} else {
			if(mp!=null) {
				mp.attachAuxEffect(id);
			}
		}
	}
	private void nextSignal(){
	   Intent i=new Intent();
	   i.setAction("com.music.ampxnative.playback.finished");
	   app.sendBroadcast(i);
	}
	private BroadcastReceiver mReceiver = new BroadcastReceiver(){

			@Override
			public void onReceive(Context arg0, Intent arg1) {
				// TODO Auto-generated method stub
				if(arg1!=null && "com.music.ampxnative.playback.finished".equals(arg1.getAction()))
					complete();
			}

		};
	public double getTrackBitrate(){
		return bitRate;
	}
	public MediaPlayer(Context context,boolean setnative){
		app=context;
		title="";
		artist="";
		album="";
		app.registerReceiver(mReceiver,new IntentFilter("com.music.ampxnative.playback.finished"));
		if(setnative){
			try{
				String dataDir=Environment.getDataDirectory().getPath()+"/data/com.music.ampxnative/lib/";
				Log.i("Ampx:JNI library","loading from"+dataDir);
				System.load(dataDir+"libavutil.so");
				System.load(dataDir+"libswresample.so");
				System.load(dataDir+"libavcodec.so");
				System.load(dataDir+"libswscale.so");
				System.load(dataDir+"libavformat.so");
				System.load(dataDir+"libavfilter.so");
				System.load(dataDir+"libndk.so");
			} catch(UnsatisfiedLinkError e){
				try{
					System.loadLibrary("avutil");
					System.loadLibrary("swresample");
					System.loadLibrary("avcodec");
					System.loadLibrary("swscale");
					System.loadLibrary("avformat");
					System.loadLibrary("avfilter");
					System.loadLibrary("ndk");
				} catch(UnsatisfiedLinkError e1){
					error=true;
				}
			}
			if(!error){
				setSamplingRate(getValidSampleRates());
				createEngine(android.os.Build.VERSION.SDK_INT);
			}
			if(!error && setnative) {
				nativeDecoder = setnative;
			}
			if(error) {
				nativeDecoder = false;
			}
		} else {
			nativeDecoder = setnative;
		}
		filterOptions=new ArrayList<WeakHashMap<String,String>>();
	}
	public void setBass(boolean state){
		bassFX=state;
	}
	public boolean getBass(){
		return bassFX;
	}
	public interface OnCompleteListener {
		 void onComplete();
		 void onPrepared();
	}
	private OnCompleteListener mOnCompleteListener;
	public void setOnCompleteListener(OnCompleteListener listener) {
		mOnCompleteListener = listener;
	}
	public void setScrobble(int type){
		scrobble=type;
	}
	public void setMetaSource(String path){
		meta=new MetaTags();
		meta.setAudio(path);
	}
	public void setMeta(String key,String value){
		meta.setMeta(key, value);
	}
	public String getMeta(String key){
		return meta.getMeta(key);
	}

	public void releaseMeta(){
		meta.release();
		meta=null;
	}
	private void complete() {
		if (mOnCompleteListener != null) {
			mOnCompleteListener.onComplete();
		}
	}
	public int getSampleRate(){
		if(getNativeDecoder()) {
			return fileData[1];
		} else {
			return 0;
		}
	}
	public void useFilter(String filter,String option) {
		boolean available=false;
		for(int i=0;i<availableFilters.length;i++){
			if(filter.toLowerCase().equals(availableFilters[i].toLowerCase())){
				available=true;
				break;
			}
		}
		if(available){
			if(filter.equals("equalizer")) {
				gain = gain + 1.0f;
			} else if(filter.equals("aecho")) {
				gain = gain + 2.4f;
			}
			 WeakHashMap <String,String> options=new WeakHashMap<String,String>();
			 options.put("filter",filter);
			 options.put("option",option);
			 filterOptions.add(options);
		}
		else{
			Log.e("Cannot find Filter", "Filter:" + filter);
		}
	}
	public void useFilter(String filter, String option, int index) {
		boolean available=false;
		for(int i=0;i<availableFilters.length;i++){
			if(filter.toLowerCase().equals(availableFilters[i].toLowerCase())){
				available=true;
				break;
			}
		}
		if(available){
			WeakHashMap <String,String> options=new WeakHashMap<String,String>();
			options.put("filter",filter);
			options.put("option", option);
			if(index>filterOptions.size()) {
				filterOptions.add(options);
			} else {
				filterOptions.add(index, options);
			}
		}
	}

	public int getDuration(){
		if(getNativeDecoder()) {
			return duration * 1000;
		} else if(mp!=null) {
			return mp.getDuration();
		}
		return 0;
	}
	public long getCurrentDuration(){
		if(getNativeDecoder()){
			return (long) Math.floor(getCurrentPosition()*1000);
		} else{
			if(mp!=null) {
				return mp.getCurrentPosition();
			}
		}
		return 0;
	}
	public void seek(int msec){
		if(getNativeDecoder()){
			flushTrack();
			seekTo((double)msec);
		} else{
			mp.seekTo(msec);
		}
	}

	public void startFilters(){
		deleteFilter();
		gain=0.0f;
		compressor=0;
		extraGain=0.0f;
		filterOptions=new ArrayList<WeakHashMap<String,String>>();
		filtering=false;
	}
	public void setNativeDecoder(boolean state){
		nativeDecoder=state;
		if(state) {
			enableFilter(1);
		} else {
			enableFilter(-1);
		}
	}
	public void setFilter(boolean state){
		if(!getNativeDecoder()) {
			return;
		}
		if(state) {
			enableFilter(1);
		} else {
			enableFilter(-1);
		}
	}

	public boolean getNativeDecoder(){
	  if(currentPlayer!=null && currentPlayer.toLowerCase().equals("ffmpeg")) {
		  return true;
	  } else if(currentPlayer!=null && currentPlayer.toLowerCase().equals("mediaplayer")) {
		  return false;
	  }
	  return nativeDecoder;
	}

	public boolean setVolume(float gain){
		if(gain<0f || gain>1f) {
			return false;
		} else{
			if(getNativeDecoder()){
				setVolumeTrack(Build.VERSION.SDK_INT, gain);
			} else{
				mp.setVolume(gain, gain);
			}
		}
		return true;
	}

	private float getParam(String str,String search){
		String[] temp=str.split(":");
		float gain=0f;
		for (String aTemp : temp) {
			if (aTemp.contains(search.toLowerCase())) {
				try {
					gain = Float.parseFloat(aTemp.substring(aTemp.indexOf("=") + 1));
				} catch (Exception e) {
					gain = 0f;
				}
			}
		}
		return gain;
	}

	@SuppressWarnings("deprecation")
	public boolean isHeadset(){
		AudioManager am=(AudioManager)app.getSystemService(Context.AUDIO_SERVICE);
		if(am.isWiredHeadsetOn()) {
			return true;
		}
		return false;
	}

	public int getCompressor(){
		return compressorLevel;
	}
	public void setCompressor(int value){
		compressor=value;
	}
	public void calcCompressor(){
		String bandFilters[]={"equalizer","bass","treble"};
		boolean lowCompressor,mediumCompressor,highCompressor;
		lowCompressor=mediumCompressor=highCompressor=false;
		for(int i=0;i<filterOptions.size();i++){
			if(Arrays.asList(bandFilters).contains(filterOptions.get(i).get("filter"))){
				float gain=getParam(filterOptions.get(i).get("option"),"gain");
				if(gain<6f)
					lowCompressor=true;
				if(gain>=6f){
					mediumCompressor=true;
					lowCompressor=false;
				}
				if(gain>=8f){
					highCompressor=true;
					mediumCompressor=false;
					lowCompressor=false;
					break;
				}
			}
		}

		String compand;
		if(highCompressor){
			if(extraCompand) {
				 compand = "attacks=0.750:decays=0.500:points=-95/-115 -75/-95 -65/-75 -55/-70 -45/-60 0/-42 5/15 25/15 40/20:soft-knee=0.2";
			} else {
				 compand = "attacks=0.750:decays=0.500:points=-95/-110 -75/-90 -65/-75 -55/-65 -45/-55 0/-30 5/15 25/15 40/20:soft-knee=0.2";
			}
			compressorLevel=3;
		} else if(mediumCompressor){
			 if(extraCompand) {
				 compand = "attacks=0.750:decays=0.500:points=-95/-110 -70/-90 -60/-80 -30/-50 0/-35 5/10 25/15 35/28 45/40:soft-knee=0.2";
			 }
			 else {
				 compand = "attacks=0.750:decays=0.500:points=-90/-100 -70/-85 -21/-31 0/-5 15/5 45/30:soft-knee=0.2";
			 }
			 compressorLevel=2;
		} else if(lowCompressor){
			 if(extraCompand) {
				 compand="attacks=0.750:decays=0.500:points=-90/-110 -80/-95 -70/-80 -50/-65 -45/-55 -35/-45 0/-28 25/20 45/40:soft-knee=0.2";
			 }
			 else {
				 compand = "attacks=0.750:decays=0.500:points=-90/-95 -85/-90 -21/-26 0/-8:soft-knee=0.3";
			 }
				compressorLevel=1;
		} else{
			compand="";
			compressorLevel=0;
		}

		if(!compand.equals("")) {
			useFilter("compand", compand, 0);
		}
		if(compressor==1) {
			compand = "attacks=0.750:decays=0.500:points=-90/-95 -85/-90 -21/-28 0/-5:soft-knee=0.2";
		} else if(compressor==2) {
			compand = "attacks=0.750:decays=0.500:points=-90/-100 -70/-80 -21/-31 0/-10 15/5 45/30:soft-knee=0.2";
		} else if(compressor==3) {
			compand = "attacks=0.750:decays=0.500:points=-65/-75 -55/-65 -45/-55 0/-15 5/15 25/15 40/20:soft-knee=0.2";
		} else {
			compand = "";
		}
		if(extraCompand) {
			gain = gain + 11.6f;
		}
		gain = gain + compressorLevel;
		if(compressorLevel==3) {
			gain = gain + 2;
		}
		if(compressor>0) {
			extraGain = extraGain + 3.0f * compressor;
		}
		if(!compand.equals("")) {
			useFilter("compand", compand);
		}
		if(gain>=20.0f) {
		   gain = 18.0f;
		}
		if(extraGain>=20.0f) {
		   extraGain = 18.0f;
		}
		if(extraGain>0.0f){
			if(gain<18.0f){
				float tmpGain=18.0f-gain;
				if(tmpGain>=extraGain){
					gain=gain+extraGain;
					extraGain=0.0f;
				} else{
					extraGain-=tmpGain;
					gain+=tmpGain;
				}
			 }
		}
		if (gain > 0.0f) {
			useFilter("volume", "volume=" + gain + "dB");
		}
		if (extraGain > 0.0f) {
			useFilter("volume", extraGain + "dB");
		}
		if(compressor>=1) {
			useFilter("volume", "volume=7dB:replaygain_preamp=9dB:replaygain=track", 1);
		}
	}
	public void setGain(float dB){
		extraGain=extraGain+dB;
	}
	public int endFilters(){
		String str="";
		for(int i=0;i<filterOptions.size();i++) {
			if(filterOptions.get(i).get("filter")!=null){
			if(filterOptions.get(i).get("option")=="")
			str+=filterOptions.get(i).get("filter")+",";
			else
			str+=filterOptions.get(i).get("filter")+"="+filterOptions.get(i).get("option")+",";
			}
		}
		if(getNativeDecoder()){
			String channels=(fileData[0]==1)?"mono":"stereo";
			String sample="s16";
			str+="aformat=sample_rates="+fileData[1]+":sample_fmts="+sample+":channel_layouts="+channels;
			int k=setFinalFilter(str);
			filtering=true;
			return k;
		}
		filtering=false;
		return -1;
	}

	//If its a equalizer filter
	public int useFilter(String filter,float gain[]) {
		int flag = 0;
		if (filter.equals(availableFilters[1])){
			float freq[]={31.25f, 62.5f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f};
			for(int i=0; i<freq.length; i++){
				if(freq[i]>=(float)fileData[1]/2) {
					break;
				}
				float q=0.808f;
				if(freq[i]<150){
					if(gain[i]>6f)
						q=0.5f;
				}
				else if(freq[i]>=150 && freq[i]<=1000f){
					if(gain[i]>5f)
						q=0.606f;
					else
						q=0.909f;
				}
				else{
					if(gain[i]>6f)
						q=0.606f;
				}
				WeakHashMap <String,String> weak=new WeakHashMap<String,String>();
				weak.put("filter","equalizer");
				weak.put("option","frequency="+freq[i]+":width_type=q:width="+q+":gain="+gain[i]);
				filterOptions.add(weak);
			}
		}
		else {
			flag = -1;
		}
		return flag;
	}

	public void setBandLevels(String gains[]){
		float gain[]=new float[gains.length];
		for(int i=0;i<gains.length;i++) {
			gain[i] = Float.parseFloat(gains[i]);
		}
		useFilter("equalizer",gain);
	}
	public void setBandLevels(float gains[]){
		useFilter("equalizer",gains);
	}
	public int setDataSource(final String filename,long id){
		trackId=id;
		setDetails(filename);
		if(getNativeDecoder()){
			fileData=getFileInfo(filename);
			if(fileData==null){
				return -1;
			}
			if(filtering){
				if(filterOptions.size()>0){
					endFilters();
				}
			}
			duration=fileData[2];
			bitRate=getBitRate();
			currentPlayer="ffmpeg";
			mOnCompleteListener.onPrepared();
		} else {
			currentPlayer="mediaplayer";
			try {
				MediaMetadataRetriever metaRetriver = new MediaMetadataRetriever();
				metaRetriver.setDataSource(filename);
				fileData[0]=2;
				fileData[1]=0;
				fileData[2]=Integer.parseInt(
				metaRetriver.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
				duration=fileData[2];
				bitRate=Double.parseDouble(metaRetriver.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
				playing=true;
				mp=new android.media.MediaPlayer();
				mp.setOnPreparedListener(this);
				mp.setOnCompletionListener(this);
				mp.setOnErrorListener(this);
				mp.setDataSource(app,Uri.parse(filename));
				mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
				metaRetriver.release();
				mp.prepare();
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				return -1;
			}
		}
		if(bitRate==0.0d){
			File f=new File(filename);
			int total=getDuration();
			long length=f.length();
			length=length*8;
			total=total/1000;
			if(total > 0) {
				bitRate = length / total;
			}
			else {
				bitRate = 0.0d;
			}
			Log.i("FFMPlayer","Format is lossless. Calculating variable bitrate:"+(bitRate/1000)+"Kbps");
		}
		metaChanged();
		released=false;
		return 0;
	}

	private void prepare(){
		runner= new Thread (pb);
		runner.start();
	}

	private void setDetails(String filePath){
		try{
			MediaMetadataRetriever metaRetriver = new MediaMetadataRetriever();
			metaRetriver.setDataSource(filePath);
			title=metaRetriver.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
			artist=metaRetriver.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
			album=metaRetriver.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
			title=(title==null)?"Unknown Title":title;
			artist=(artist==null)?"Unknown Artist":artist;
			album=(album==null)?"Unknown Title":album;
			metaRetriver.release();
		}
		catch(Exception e){
			title="Unknown Title";
			artist="Unknown Artist";
			album="Unknown Title";
		}
	}

	private void metaChanged(){
		Intent intent = new Intent();
		intent.setAction("com.android.music.metachanged");
		Bundle bundle = new Bundle();
		// put the song's metadata
		bundle.putString("track", title);
		bundle.putString("artist", artist);
		bundle.putString("album", album);
		// put the song's total duration (in ms)
		bundle.putLong("duration", (long)getDuration()); // 4:05
		// put the song's current position
		bundle.putLong("position", (long) getCurrentDuration()); // 0:30
		// put the playback status
		bundle.putBoolean("playing", playing()); // currently playing
		// put your application's package
		bundle.putString("scrobbling_source", app.getPackageName());
		intent.putExtras(bundle);
		app.sendBroadcast(intent);

		scrobbleStart();
	}

	private void scrobbleStart(){
		if(scrobble==1){
			Intent i = new Intent("net.jjc1138.android.scrobbler.action.MUSIC_STATUS");
			i.putExtra("id", trackId);
			i.putExtra("playing", true);
			i.putExtra("album", album);
			i.putExtra("artist", artist);
			i.putExtra("track", title);
			i.putExtra("secs", (int)getDuration()/1000);
			app.sendBroadcast(i);
		} else{
			Intent bCast = new Intent("com.adam.aslfms.notify.playstatechanged");
			bCast.putExtra("state", START);
			bCast.putExtra("app-name", "AmpX");
			bCast.putExtra("app-package", "com.music.ampxnative");
			bCast.putExtra("artist", artist);
			bCast.putExtra("album", album);
			bCast.putExtra("track", title);
			bCast.putExtra("duration", (int)getDuration()/1000);
			app.sendBroadcast(bCast);
		}
	}

	private void scrobbleMid(){
		if(scrobble==1){
			Intent i = new Intent("net.jjc1138.android.scrobbler.action.MUSIC_STATUS");
			i.putExtra("id", trackId);
			i.putExtra("playing", playing());
			i.putExtra("album", album);
			i.putExtra("artist", artist);
			i.putExtra("track", title);
			i.putExtra("secs", (int)getDuration()/1000);
			app.sendBroadcast(i);
		} else{
			Intent bCast = new Intent("com.adam.aslfms.notify.playstatechanged");
			if(playing())
			bCast.putExtra("state", RESUME);
			else
			bCast.putExtra("state", PAUSE);
			bCast.putExtra("app-name", "AmpX");
			bCast.putExtra("app-package", app.getPackageName());
			bCast.putExtra("artist", artist);
			bCast.putExtra("album", album);
			bCast.putExtra("track", title);
			bCast.putExtra("duration", (int)getDuration()/1000);
			app.sendBroadcast(bCast);
		}
	}

	private void scrobbleEnd(){
		if(scrobble==1){
			Intent i = new Intent("net.jjc1138.android.scrobbler.action.MUSIC_STATUS");
			i.putExtra("id", trackId);
			i.putExtra("playing", false);
			i.putExtra("album", album);
			i.putExtra("artist", artist);
			i.putExtra("track", title);
			i.putExtra("secs", (int)getDuration()/1000);
			app.sendBroadcast(i);
		} else{
			Intent bCast = new Intent("com.adam.aslfms.notify.playstatechanged");
			bCast.putExtra("state", COMPLETE);
			bCast.putExtra("app-name", "AmpX");
			bCast.putExtra("app-package", "com.music.ampxnative");
			bCast.putExtra("artist", artist);
			bCast.putExtra("album", album);
			bCast.putExtra("track", title);
			bCast.putExtra("duration", (int)getDuration()/1000);
			app.sendBroadcast(bCast);
		}
	}

	private void playStateChanged(boolean playing){
		Intent intent = new Intent();
		intent.setAction("com.android.music.playstatechanged");
		Bundle bundle = new Bundle();
		// put the song's metadata
		bundle.putString("track", title);
		bundle.putString("artist", artist);
		bundle.putString("album", album);
		// put the song's total duration (in ms)
		bundle.putLong("duration", (long)getDuration()); // 4:05
		// put the song's current position
		bundle.putLong("position", (long)getCurrentDuration()); // 0:30
		// put the playback status
		bundle.putBoolean("playing",playing); // currently playing
		// put your application's package
		bundle.putString("scrobbling_source", app.getPackageName());
		intent.putExtras(bundle);
		app.sendBroadcast(intent);
		scrobbleMid();
	}

	public void play(){
		if(!getNativeDecoder()){
			startFx();
			if(mp!=null && !mp.isPlaying()) {
				mp.start();
			}
			playing=true;
		} else {
			if(getPlayState()!=AudioTrack.PLAYSTATE_PLAYING && getPlayState()!=AudioTrack.STATE_UNINITIALIZED) {
			   prepare();
			   playTrack();
			}
		}
		playStateChanged(true);
		sendBroadcast(PLAYER_STARTED);
	}

	public void pause(){
		if(!getNativeDecoder()){
			stopFx();
			if(mp!=null && mp.isPlaying()) {
				mp.pause();
			}
				playing=false;
		} else {
			if(getPlayState()==AudioTrack.PLAYSTATE_PLAYING &&
			   getPlayState()!=AudioTrack.STATE_UNINITIALIZED) {
				pauseTrack();
			}

			try {
				runner.interrupt();
				runner.join();
			} catch (NullPointerException | InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		playStateChanged(false);
		sendBroadcast(PLAYER_PAUSED);
	}

	public void gc(){
		app.unregisterReceiver(mReceiver);
	}
	public void stop(){
		if(!getNativeDecoder()) {
			stopFx();
			if(mp.isPlaying()) {
				mp.stop();
			}
			playing=false;
		} else {
			 if (getPlayState() != AudioTrack.STATE_UNINITIALIZED) {
				stopTrack();
			 }
			 if (runner != null && runner.isAlive()) {
				runner.interrupt();
			 try {
				runner.join();
			 } catch (InterruptedException e) {
				 // TODO Auto-generated catch block
				e.printStackTrace();
			 }
				seekTo(0);
			 }
		}
		playStateChanged(false);
		sendBroadcast(PLAYER_PAUSED);
	}

	public int getAudioSessionId(){
		if(getNativeDecoder()){
			return getTrackSessionId();
		} else {
			if (mp != null) {
				 return mp.getAudioSessionId();
			} else {
				 return 0;
			}
		}
	}

	public int getPosn() {
		if(getNativeDecoder()) {
			return getTrackPlaybackHeadPosition();
		} else if(mp!=null) {
			return mp.getCurrentPosition();
		}
		return 0;
	}

	public boolean playing(){
		if(getNativeDecoder()){
			if(getPlayState()!=AudioTrack.PLAYSTATE_PLAYING) {
				return false;
			} else {
				return true;
			}
		}
		return playing;
	}
	private void stopAudioTrack(){
		if(getPlayState()!=AudioTrack.STATE_UNINITIALIZED) {
			stop();
		}
		flushTrack();
		destroy();
	}
	public boolean isReleased(){
		return released;
	}
	public int release(){
		if(currentPlayer==null) {
			return -1;
		}
		scrobbleEnd();
		if(currentPlayer.toLowerCase().equals("ffmpeg")){
			stopAudioTrack();
		} else if(currentPlayer.toLowerCase().equals("mediaplayer")){
			stop();
			mp.reset();
			mp.release();
			mp=null;
		}
		released=true;
		sendBroadcast(PLAYER_RELEASED);
		return 1;
	}
	private void stopFx(){
		final Intent intent = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
		intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
		intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, app.getPackageName());
		app.sendBroadcast(intent);
	}
	private void startFx(){
		final Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
		i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
		i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, app.getPackageName());
		app.sendBroadcast(i);
	}
	@Override
	public void onCompletion(android.media.MediaPlayer  arg0) {
		// TODO Auto-generated method stub
		nextSignal();
	}
	@Override
	public void onPrepared(android.media.MediaPlayer mp) {
		// TODO Auto-generated method stub
		mOnCompleteListener.onPrepared();
	}
	@Override
	public boolean onError(android.media.MediaPlayer mp, int what, int extra) {
		// TODO Auto-generated method stub
		nextSignal();
		Toast.makeText(app, app.getString(R.string.playback_failed), Toast.LENGTH_SHORT).show();
		return false;
	}
}
