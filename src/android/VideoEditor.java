package org.apache.cordova.videoeditor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.ArrayList;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.PluginResult;
import org.ffmpeg.android.ShellUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.ffmpeg.android.FfmpegController;
import org.ffmpeg.android.Clip;
import org.ffmpeg.android.ShellUtils.ShellCallback;

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;

/**
 * VideoEditor plugin for Android
 * Created by Ross Martin 2-2-15
 */
public class VideoEditor extends CordovaPlugin {

    private static final String TAG = "VideoEditor";

    private CallbackContext callback;

    private static final int HighQuality = 0;
    private static final int MediumQuality = 1;
    private static final int LowQuality = 2;

    private static final int M4V = 0;
    private static final int MPEG4 = 1;
    private static final int M4A = 2;
    private static final int QUICK_TIME = 3;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "execute method starting");

        this.callback = callbackContext;

        if (action.equals("transcodeVideo")) {
            try {
                this.transcodeVideo(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("trim")) {
            try {
                this.trim(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("createThumbnail")) {
            try {
                this.createThumbnail(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        } else if (action.equals("execFFMPEG")) {
            try {
                this.execFFMPEG(args);
            } catch (IOException e) {
                callback.error(e.toString());
            }
            return true;
        }
        return false;
    }

    /**
     * transcodeVideo
     *
     * Transcodes a video
     *
     * ARGUMENTS
     * =========
     *
     * fileUri:         - path to input video
     * outputFileName:  - output file name
     * quality:         - transcode quality
     * outputFileType:  - output file type
     * saveToLibrary:   - save to gallery
     * deleteInputFile: - optionally remove input file
     *
     * RESPONSE
     * ========
     *
     * outputFilePath - path to output file
     *
     * @param JSONArray args
     * @return void
     */
    private void transcodeVideo(JSONArray args) throws JSONException, IOException {
        Log.d(TAG, "transcodeVideo firing");

        JSONObject options = args.optJSONObject(0);
        Log.d(TAG, "options: " + options.toString());

        final File inFile = this.resolveLocalFileSystemURI(options.getString("fileUri"));
        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input video does not exist.");
            return;
        }

        final String videoSrcPath = inFile.getAbsolutePath();
        final String outputFileName = options.optString(
            "outputFileName",
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date())
        );
        final int videoQuality = options.optInt("quality", HighQuality);
        final int outputType = options.optInt("outputFileType", MPEG4);

        Log.d(TAG, "videoSrcPath: " + videoSrcPath);

        String outputExtension;
        // arbitrary values used for ffmpeg, tailor to your needs
        final int outputWidth;
        final int outputHeight;

        switch(outputType) {
            case QUICK_TIME:
                outputExtension = ".mov";
                break;
            case M4A:
                outputExtension = ".m4a";
                break;
            case M4V:
                outputExtension = ".m4v";
                break;
            case MPEG4:
            default:
                outputExtension = ".mp4";
                break;
        }

        switch(videoQuality) {
            case LowQuality:
                outputWidth = 320;
                outputHeight = 320;
                break;
            case MediumQuality:
                outputWidth = 480;
                outputHeight = 480;
                break;
            case HighQuality:
            default:
                outputWidth = 640;
                outputHeight = 640;
                break;
        }

        final Context appContext = cordova.getActivity().getApplicationContext();
        final PackageManager pm = appContext.getPackageManager();

        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(cordova.getActivity().getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            ai = null;
        }
        final String appName = (String) (ai != null ? pm.getApplicationLabel(ai) : "Unknown");

        final boolean saveToLibrary = options.optBoolean("saveToLibrary", true);
        File mediaStorageDir;

        if (saveToLibrary) {
            mediaStorageDir = new File(
                Environment.getExternalStorageDirectory() + "/Movies",
                appName
            );
        } else {
            mediaStorageDir = new File(appContext.getExternalCacheDir().getPath());
        }

        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdir()) {
                callback.error("Can't access or make Movies directory");
                return;
            }
        }

        final String outputFilePath =  new File(
            mediaStorageDir.getPath(),
            "VID_" + outputFileName + outputExtension
        ).getAbsolutePath();

        Log.d(TAG, "outputFilePath: " + outputFilePath);

        final double videoDuration = options.optDouble("duration", 0);
        final boolean deleteInputFile = options.optBoolean("deleteInputFile", false);

        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    File tempFile = File.createTempFile("ffmpeg", null, appContext.getCacheDir());
                    FfmpegController ffmpegController = new FfmpegController(appContext, tempFile);

                    Clip clipIn = new Clip(videoSrcPath);

                    Clip clipOut = new Clip(outputFilePath);
                    clipOut.videoCodec = "libx264";
                    clipOut.videoFps = "30"; // Original FPS 24, ours 30, tailor this to your needs 
                    clipOut.videoBitrate = 2560; // Original Bitrate: 512 kbps - tailor this to your needs
                    // Our bitrate: 1024 + 1024 + 512 = 2048 + 512 = 2560
                    clipOut.audioChannels = 1;
                    clipOut.width = outputWidth;
                    clipOut.height = outputHeight;
                    clipOut.duration = videoDuration;

                    ffmpegController.processVideo(clipIn, clipOut, true, new ShellUtils.ShellCallback() {
                        @Override
                        public void shellOut(String shellLine) {
                            Log.d(TAG, "shellOut: " + shellLine);
                            try {
                                JSONObject jsonObj = new JSONObject();
                                jsonObj.put("progress", shellLine.toString());
                                PluginResult progressResult = new PluginResult(PluginResult.Status.OK, jsonObj);
                                progressResult.setKeepCallback(true);
                                callback.sendPluginResult(progressResult);
                            } catch (JSONException e) {
                                Log.d(TAG, "PluginResult error: " + e);
                            }
                        }
                        @Override
                        public void processComplete(int exitValue) {}
                    });

                    Log.d(TAG, "ffmpeg finished");

                    File outFile = new File(outputFilePath);
                    if (!outFile.exists()) {
                        Log.d(TAG, "outputFile doesn't exist!");
                        callback.error("an error ocurred during transcoding");
                        return;
                    }

                    // make the gallery display the new file if saving to library
                    if (saveToLibrary) {
                        Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                        scanIntent.setData(Uri.fromFile(inFile));
                        scanIntent.setData(Uri.fromFile(outFile));
                        appContext.sendBroadcast(scanIntent);
                    }

                    if (deleteInputFile) {
                        inFile.delete();
                    }

                    callback.success(outputFilePath);
                } catch (Throwable e) {
                    Log.d(TAG, "transcode exception ", e);
                    callback.error(e.toString());
                }
            }
        });
    }

    /**
     * trim
     *
     * Performs a fast-trim operation on an input clip.
     *
     * ARGUMENTS
     * =========
     *
     * fileUri      - path to input video
     * trimStart      - time to start trimming
     * trimEnd        - time to end trimming
     * outputFileName - output file name
     *
     * RESPONSE
     * ========
     *
     * outputFilePath - path to output file
     *
     * @param JSONArray args
     * @return void
     */
    private void trim(JSONArray args) throws JSONException, IOException {
        Log.d(TAG, "trim firing");

        // parse arguments
        JSONObject options = args.optJSONObject(0);

        Log.d(TAG, "options: " + options.toString());

        // outputFileName
        final String outputFileName = options.optString(
                "outputFileName",
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date())
        );
        final String inputFilePath = options.getString("fileUri");

        // outputFileExt
        final String outputFileExt = this.getFileExt(inputFilePath);

        // inputFile
        final File inFile = this.resolveLocalFileSystemURI(inputFilePath);
        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input video does not exist.");
            return;
        }

        // trim points
        double trim0 = options.optDouble("trimStart");
        final String trimstart = this.durationFormat(trim0);
        double trimend = options.getDouble("trimEnd");
        trimend = trimend - trim0;
        if(trimend == 0){
            callback.error("trim: failed to trim video; duration is 0");
            return;
        }
        final String duration = this.durationFormat(trimend);

        // tempDir
        final Context appContext = cordova.getActivity().getApplicationContext();
        final File tempDir = this.getTempDir(appContext, outputFileExt);

        // outputFilePath
        final File outputFile = new File(tempDir, outputFileName + outputFileExt);
        final String outputFilePath = outputFile.getAbsolutePath();

        // start task
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    FfmpegController ffmpegController = new FfmpegController(appContext, tempDir);

                    // ffmpeg -ss [start1] -i [INPUT] -ss [start2] -t [duration] -c copy [OUTPUT]
                    ArrayList<String> cmd = new ArrayList<String>();
                    cmd.add(ffmpegController.getBinaryPath());
                    // fast, inaccurate trim
                    cmd.add("-ss");
                    cmd.add(trimstart);
                    // input
                    cmd.add("-i");
                    cmd.add(inFile.getCanonicalPath());
                    // duration
                    cmd.add("-t");
                    cmd.add(duration);
                    // copy audio, video
                    cmd.add("-c");
                    cmd.add("copy");

                    cmd.add(outputFilePath);
                    ffmpegController.execFFMPEG(cmd, new ShellUtils.ShellCallback() {
                        @Override
                        public void shellOut(String shellLine) {
                            Log.d(TAG, "shellOut: " + shellLine);
                            try {
                                JSONObject jsonObj = new JSONObject();
                                jsonObj.put("progress", shellLine.toString());
                                PluginResult progressResult = new PluginResult(PluginResult.Status.OK, jsonObj);
                                progressResult.setKeepCallback(true);
                                callback.sendPluginResult(progressResult);
                            } catch (JSONException e) {
                                Log.d(TAG, "PluginResult error: " + e);
                            }
                        }
                        @Override
                        public void processComplete(int exitValue) {}
                    });

                    Log.d(TAG, "ffmpeg finished");
                    if (!outputFile.exists()) {
                        Log.d(TAG, "outputFile doesn't exist!");
                        callback.error("trim: failed to trim video");
                        return;
                    }

                    callback.success(outputFilePath);
                } catch (Throwable e) {
                    Log.d(TAG, "transcode exception ", e);
                    callback.error(e.toString());
                }
            }
        });

    }

    private class TranscodeCallback implements ShellCallback {

        @Override
        public void shellOut(String shellLine) {
            Log.d(TAG, "shellOut: " + shellLine);
        }

        @Override
        public void processComplete(int exitValue) {
            Log.d(TAG, "processComplete: " + exitValue);
        }

    }

    @SuppressWarnings("unused")
    private void createThumbnail(JSONArray args) throws JSONException, IOException {
        Log.d(TAG, "createThumbnail firing");
        /*  createThumbnail arguments:
         fileUri: video input url
         outputFileName: output file name
         */

        JSONObject options = args.optJSONObject(0);
        Log.d(TAG, "options: " + options.toString());

        String fileUri = options.getString("fileUri");
        if (!fileUri.startsWith("file:/")) {
            fileUri = "file:/" + fileUri;
        }

        File inFile = this.resolveLocalFileSystemURI(fileUri);
        if (!inFile.exists()) {
            Log.d(TAG, "input file does not exist");
            callback.error("input video does not exist.");
            return;
        }
        String srcVideoPath = inFile.getAbsolutePath();
        String outputFileName = options.optString(
            "outputFileName",
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ENGLISH).format(new Date())
        );

        Context appContext = cordova.getActivity().getApplicationContext();
        PackageManager pm = appContext.getPackageManager();

        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(cordova.getActivity().getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            ai = null;
        }
        final String appName = (String) (ai != null ? pm.getApplicationLabel(ai) : "Unknown");

        File tempStoragePath = appContext.getExternalCacheDir();

        File outputFile =  new File(
            tempStoragePath.getPath(),
            "PIC_" + outputFileName + ".jpg"
        );

        Bitmap thumbnail = ThumbnailUtils.createVideoThumbnail(srcVideoPath, MediaStore.Images.Thumbnails.MINI_KIND);

        FileOutputStream theOutputStream;
        try {
            if (!outputFile.exists()) {
                if (!outputFile.createNewFile()) {
                    callback.error("Could not save thumbnail.");
                }
            }
            if (outputFile.canWrite()) {
                theOutputStream = new FileOutputStream(outputFile);
                if (theOutputStream != null) {
                    thumbnail.compress(CompressFormat.JPEG, 75, theOutputStream);
                } else {
                    callback.error("Could not save thumbnail; target not writeable");
                }
            }
        } catch (IOException e) {
            callback.error(e.toString());
        }

        callback.success(outputFile.getAbsolutePath());
    }

    /**
     * execFFMPEG
     *
     * Executes an ffmpeg command
     *
     * ARGUMENTS
     * =========
     *
     * cmd - ffmpeg command as a string array
     *
     * RESPONSE
     * ========
     *
     * VOID
     *
     * @param JSONArray args
     * @return void
     */
    @SuppressWarnings("unused")
    private void execFFMPEG(JSONArray args) throws JSONException, IOException {
        Log.d(TAG, "execFFMPEG firing");

        // parse arguments
        JSONObject options = args.optJSONObject(0);

        Log.d(TAG, "options: " + options.toString());

        final JSONArray cmds = options.getJSONArray("cmd");
        final Context appContext = cordova.getActivity().getApplicationContext();

        // start task
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    File tempFile = File.createTempFile("ffmpeg", null, appContext.getCacheDir());
                    FfmpegController ffmpegController = new FfmpegController(appContext, tempFile);

                    ArrayList<String> al = new ArrayList<String>();
                    al.add(ffmpegController.getBinaryPath());

                    int cmdArrLength = cmds.length();
                    for (int i = 0; i < cmdArrLength; i++) {
                        al.add(cmds.optString(i));
                    }

                    ffmpegController.execFFMPEG(al, new ShellUtils.ShellCallback() {
                        @Override
                        public void shellOut(String shellLine) {
                            Log.d(TAG, "shellOut: " + shellLine);
                            try {
                                JSONObject jsonObj = new JSONObject();
                                jsonObj.put("progress", shellLine.toString());
                                PluginResult progressResult = new PluginResult(PluginResult.Status.OK, jsonObj);
                                progressResult.setKeepCallback(true);
                                callback.sendPluginResult(progressResult);
                            } catch (JSONException e) {
                                Log.d(TAG, "PluginResult error: " + e);
                            }
                        }
                        @Override
                        public void processComplete(int exitValue) {}
                    });
                    Log.d(TAG, "ffmpeg finished");

                    callback.success();
                } catch (Throwable e) {
                    Log.d(TAG, "ffmpeg exception ", e);
                    callback.error(e.toString());
                }
            }
        });
    }

    @SuppressWarnings("deprecation")
    private File resolveLocalFileSystemURI(String url) throws IOException, JSONException {
        String decoded = URLDecoder.decode(url, "UTF-8");

        File fp = null;

        // Handle the special case where you get an Android content:// uri.
        if (decoded.startsWith("content:")) {
            fp = new File(getPath(this.cordova.getActivity().getApplicationContext(), Uri.parse(decoded)));
        } else {
            // Test to see if this is a valid URL first
            @SuppressWarnings("unused")
            URL testUrl = new URL(decoded);

            if (decoded.startsWith("file://")) {
                int questionMark = decoded.indexOf("?");
                if (questionMark < 0) {
                    fp = new File(decoded.substring(7, decoded.length()));
                } else {
                    fp = new File(decoded.substring(7, questionMark));
                }
            } else if (decoded.startsWith("file:/")) {
                fp = new File(decoded.substring(6, decoded.length()));
            } else {
                fp = new File(decoded);
            }
        }

        if (!fp.exists()) {
            throw new FileNotFoundException();
        }
        if (!fp.canRead()) {
            throw new IOException();
        }
        return fp;
    }

    /**
     * Get a file path from a Uri. This will get the the path for Storage Access
     * Framework Documents, as well as the _data field for the MediaStore and
     * other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @author paulburke
     */
    public static String getPath(final Context context, final Uri uri) {

        final boolean isKitKat = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT;

        // DocumentProvider
        if (isKitKat && DocumentsContract.isDocumentUri(context, uri)) {
            // ExternalStorageProvider
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                if ("primary".equalsIgnoreCase(type)) {
                    return Environment.getExternalStorageDirectory() + "/" + split[1];
                }

                // TODO handle non-primary volumes
            }
            // DownloadsProvider
            else if (isDownloadsDocument(uri)) {

                final String id = DocumentsContract.getDocumentId(uri);
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));

                return getDataColumn(context, contentUri, null, null);
            }
            // MediaProvider
            else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];

                Uri contentUri = null;
                if ("image".equals(type)) {
                    contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }

                final String selection = "_id=?";
                final String[] selectionArgs = new String[] {
                        split[1]
                };

                return getDataColumn(context, contentUri, selection, selectionArgs);
            }
        }
        // MediaStore (and general)
        else if ("content".equalsIgnoreCase(uri.getScheme())) {
            return getDataColumn(context, uri, null, null);
        }
        // File
        else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }

        return null;
    }

    /**
     * Get the value of the data column for this Uri. This is useful for
     * MediaStore Uris, and other file-based ContentProviders.
     *
     * @param context The context.
     * @param uri The Uri to query.
     * @param selection (Optional) Filter used in the query.
     * @param selectionArgs (Optional) Selection arguments used in the query.
     * @return The value of the _data column, which is typically a file path.
     */
    public static String getDataColumn(Context context, Uri uri, String selection,
            String[] selectionArgs) {

        Cursor cursor = null;
        final String column = "_data";
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, selection, selectionArgs,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                final int column_index = cursor.getColumnIndexOrThrow(column);
                return cursor.getString(column_index);
            }
        } finally {
            if (cursor != null)
                cursor.close();
        }
        return null;
    }


    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is ExternalStorageProvider.
     */
    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is DownloadsProvider.
     */
    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    /**
     * @param uri The Uri to check.
     * @return Whether the Uri authority is MediaProvider.
     */
    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    /**
     *
     * Shotclip Additions
     *
     *
     */

    /**
     * getOutputFilePath
     *
     * Resolves the output file path.
     *
     * @param String outputFileName
     * @param String outputExtension
     * @param boolean saveToLibrary
     * @return String
     */
    private File getOutputFilePath(String outputFileName, String outputExtension, boolean saveToLibrary) throws JSONException, IOException {
        final Context appContext = cordova.getActivity().getApplicationContext();
        final PackageManager pm = appContext.getPackageManager();

        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(cordova.getActivity().getPackageName(), 0);
        } catch (final NameNotFoundException e) {
            ai = null;
        }
        final String appName = (String) (ai != null ? pm.getApplicationLabel(ai) : "Unknown");

        File mediaStorageDir;

        if (saveToLibrary) {
            mediaStorageDir = new File(
                    Environment.getExternalStorageDirectory() + "/Movies",
                    appName
            );
        } else {
            mediaStorageDir = appContext.getExternalCacheDir();
        }

        if (!mediaStorageDir.exists()) {
            if (!mediaStorageDir.mkdirs()) {
                callback.error("Can't access or make Movies directory");
                return null;
            }
        }

        // outputFile
        File outputFile = new File(mediaStorageDir.getAbsolutePath(), "VID_" + outputFileName + outputExtension);
        Log.v(TAG, "outputFilePath: " + outputFile.getAbsolutePath());

        return outputFile;
    }

    /**
     * durationFormat
     *
     * Formats a double timestamp into a string ready for
     * use in ffmpeg
     *
     * @param double duration
     * @return void
     */
    private String durationFormat(double duration){
        String res = new String();
        int hours = (int)(duration/3600f);
        duration -= (hours*3600);
        int min = (int)(duration/60f);
        duration -= (min*60);
        res =  "0" + String.format(Locale.US, "%s", hours) + ":";
        res += "0" + String.format(Locale.US, "%s", min) + ":";
        res += String.format(Locale.US, "%s", duration);
        return res;
    };

    /**
     * getFileExt
     *
     * Gets the file extension from a filename
     *
     * @param String filename
     * @return String
     */
    private String getFileExt(String filename){
        try {
            return filename.substring(filename.lastIndexOf("."));

        }
        catch (Exception e) {
            return "";
        }
    };

    /**
     * getTempDir
     *
     * Make a temp directory for storing intermediate files.
     * Named after file type, eg 'mp4', 'ts')
     *
     * @param Context appContext
     * @param String ext
     * @return File
     */
    private File getTempDir(Context appContext, String ext){
        final File tempDir = new File(appContext.getCacheDir(), ext.substring(1));
        if(!tempDir.exists()){
            if (!tempDir.mkdirs()) {
                callback.error("Can't access or make temporary cache directory");
                return null;
            }
        }
        return tempDir;
    }

}
