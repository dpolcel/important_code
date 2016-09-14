package br.com.bee.android;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

import org.json.JSONArray;

import br.com.bee.android.model.ITaskManager;
import br.com.bee.android.model.MyApplication;
import br.com.bee.android.model.ServiceInterface;
import br.com.bee.android.model.list.ChatPostAdapter;
import br.com.bee.android.model.list.ChatPostItem;
import br.com.bee.android.model.list.ListViewLoadingFromHeader;
import br.com.bee.android.model.list.ListViewLoadingFromHeader.OnLoadMoreListener;
import br.com.bee.android.model.user.NetworkSettings;
import br.com.bee.android.model.user.UserData;
import br.com.bee.android.ui.ViewProxy;
import br.com.bee.android.util.AudioUtilities;
import br.com.bee.android.util.PlayingState;
import br.com.bee.android.util.Settings;
import br.com.bee.android.util.Toaster;

import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.AsyncTask.Status;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Vibrator;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HeaderViewListAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class ChatActivity extends Activity implements ITaskManager, ChatPostAdapter.OnPlayPauseButtonClickListener, ChatPostAdapter.OnSeekBarChangeListener {
    public static final String ARG_OTHER_USER_ID = "other_user_id";
    public static final String ARG_OTHER_USER_NAME = "other_user_name";
    private static final String ARG_REF_DATE_TIME = "ref_datetime";

    private ListViewLoadingFromHeader mList;
    private EditText mMessage;
    private ImageButton mSendButton;
    private LoadingNewTask mLoadingNewTask = null;
    private LoadingMoreTask mLoadingMoreTask = null;
    private SendingTask mSendingTask = null;
    private long mOtherUserID;
    private Long mLastViewDateTicks;
    private boolean mIsClickBlocked = false;
    private boolean mIsFirstLoading = false;

    private Handler mRefreshPostsHandler = null;
    private Runnable mRefreshPostsRunnable;
    private static final int REFRESH_POSTS_TIMER = 7000;
    private static final int PLAYING_HANDLER_POST_DELAYED = 1000;

    //<editor-fold desc="Record audio variables and methods">
    private static final String LOG_TAG = "VoiceMessage";
    private final int MAX_RECORDING_TOAST_WARNING = 6;
    private String RECORDED_FILE_NAME_PREFIX = "4BEE_VOICE_MESSAGE_%d.mp3";
    private File mAudiofile;
    private MediaPlayer mp;

    private MediaRecorder mRecorder = null;
    private Handler mHandler = new Handler();

    private String mFileName = null;
    private long mStartTime = 0L;
    private long mTimeInMilliseconds = 0L;
    private long mTimeSwapBuff = 0L;
    private long mUpdatedTime = 0L;

    TextView slideToCancel;
    private TextView recordTimeText;
    private ImageView recImage;
    private ImageButton audioSendButton;
    private View slideText;
    private float startedDraggingX = -1;
    private float distCanMove = dp(80);
    private FrameLayoutFixed record_panel;
    private boolean mMaxDurationReached = false;
    private static Toast mCountDownToast;

    //</editor-fold>

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        Intent intent = getIntent();
        mOtherUserID = intent.getLongExtra(ARG_OTHER_USER_ID, -1);
        getActionBar().setTitle(intent.getStringExtra(ARG_OTHER_USER_NAME));

        mList = (ListViewLoadingFromHeader) findViewById(R.id.chat_posts_list);

        configureRecordingObjects();

        mSendButton = (ImageButton) findViewById(R.id.chat_posts_send_button);

        mMessage = (EditText) findViewById(R.id.chat_posts_message);
        mMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                //do nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!s.toString().isEmpty()) {
                    audioSendButton.setVisibility(View.GONE);
                    mSendButton.setVisibility(View.VISIBLE);
                } else {
                    audioSendButton.setVisibility(View.VISIBLE);
                    mSendButton.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                //do nothing
            }
        });


        final ChatPostAdapter adapter = new ChatPostAdapter(this, new ArrayList<ChatPostItem>());
        adapter.setPlayPauseButtonListener(ChatActivity.this);
        adapter.setSeekBarChangeListener(ChatActivity.this);

        mList.setAdapter(adapter);
        mSendButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mIsClickBlocked) {
                    mIsClickBlocked = true;

                    if (mSendingTask == null || mSendingTask.getStatus() == Status.FINISHED) {
                        mMessage.setEnabled(false);
                        mSendingTask = new SendingTask();
                        mSendingTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);

                        audioSendButton.setVisibility(View.VISIBLE);
                        mSendButton.setVisibility(View.GONE);
                    } else {
                        mIsClickBlocked = false;
                    }
                }
            }
        });


        // Set a listener to be invoked when the list should load more items.
        mList.setOnLoadMoreListener(new OnLoadMoreListener() {
            @Override
            public void onLoadMore() {
                loadList(false);
            }
        });

        MyApplication myApp = (MyApplication) getApplication();
        getActionBar().setBackgroundDrawable(new ColorDrawable(Color.parseColor(myApp.getUser().getThemeColor())));
        getActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(ARG_OTHER_USER_ID, mOtherUserID);
        if (mLastViewDateTicks != null) {
            outState.putLong(ARG_REF_DATE_TIME, mLastViewDateTicks);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mOtherUserID = savedInstanceState.getLong(ARG_OTHER_USER_ID);
        if (savedInstanceState.containsKey(ARG_REF_DATE_TIME)) {
            mLastViewDateTicks = savedInstanceState.getLong(ARG_REF_DATE_TIME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();


        if (mRefreshPostsHandler != null && mRefreshPostsRunnable != null) {
            mRefreshPostsHandler.removeCallbacks(mRefreshPostsRunnable);
        }

        //Stop an eventually recording voice message
        cancelRecording();

        //Stop an eventually playing voice message
        mp.reset();
        ChatPostAdapter.mPlayingPosition = -1;
        if (ChatPostAdapter.mPlayingHandler != null) {
            ChatPostAdapter.mPlayingHandler.removeCallbacks(ChatPostAdapter.mPlayingRunnable);
        }

        MyApplication myApp = (MyApplication) getApplication();
        UserData user = myApp.getUser();
        user.setCurrentChatOtherUserID(null);
        myApp.saveUserData();
        myApp.setCurrentChatActivity(null);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent intent = getIntent();
        if (intent != null) {
            if (mOtherUserID != intent.getLongExtra(ARG_OTHER_USER_ID, -1)) {
                mOtherUserID = intent.getLongExtra(ARG_OTHER_USER_ID, -1);
                getActionBar().setTitle(intent.getStringExtra(ARG_OTHER_USER_NAME));
            }
        }

        mList = (ListViewLoadingFromHeader) findViewById(R.id.chat_posts_list);
        mMessage = (EditText) findViewById(R.id.chat_posts_message);
        mSendButton = (ImageButton) findViewById(R.id.chat_posts_send_button);
        mIsClickBlocked = false;

        if (mRefreshPostsRunnable == null) {
            mRefreshPostsRunnable = new Runnable() {

                @Override
                public void run() {
                    loadList(true);
                }
            };
            if (mRefreshPostsHandler == null) {
                mRefreshPostsHandler = new Handler();
            }
        }
        if (mLastViewDateTicks == null) {
            mIsFirstLoading = true;
            if ((((HeaderViewListAdapter) mList.getAdapter()).getWrappedAdapter()).getCount() == 0) {
                mList.prepareForLoadMore();
            }
        }
        mRefreshPostsHandler.postDelayed(mRefreshPostsRunnable, 0);

        ((NotificationManager) this.getSystemService(Context.NOTIFICATION_SERVICE)).cancel((int) mOtherUserID);

        MyApplication myApp = (MyApplication) getApplication();
        UserData user = myApp.getUser();
        user.setCurrentChatOtherUserID(mOtherUserID);
        myApp.saveUserData();
        myApp.setCurrentChatActivity(this);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        // getMenuInflater().inflate(R.menu.send_post, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    public void onStop() {
        stopTasks();
        cancelRecording();
        super.onStop();
    }

    public void loadList(boolean loadOnlyNewPosts) {
        if (loadOnlyNewPosts) {
            if (mLoadingNewTask == null || mLoadingNewTask.getStatus() == Status.FINISHED) {
                mLoadingNewTask = new LoadingNewTask();
                mLoadingNewTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
            }
        } else {
            if (mLoadingMoreTask == null || mLoadingMoreTask.getStatus() == Status.FINISHED) {
                if (!mList.isLoadingMore()) {
                    mList.prepareForLoadMore();
                }

                mLoadingMoreTask = new LoadingMoreTask();
                mLoadingMoreTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
            } else {
                mList.onLoadMoreFailed();
            }
        }
    }

    /**
     * Represents an asynchronous loading task.
     */
    private class LoadingNewTask extends AsyncTask<Void, Void, Boolean> {
        ArrayList<ChatPostItem> arrResultItems = new ArrayList<ChatPostItem>();
        JSONArray objJSONResponse;

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (mRefreshPostsHandler != null) {
                mRefreshPostsHandler.removeCallbacks(mRefreshPostsRunnable);
            }
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                MyApplication myApp = (MyApplication) getApplication();
                ServiceInterface objService = new ServiceInterface(myApp.getUser());

                if (myApp.isOnlineAndServerReachable()) {
                    if (mLastViewDateTicks == null) {
                        mLastViewDateTicks = objService.getChatRoomLastVerification(mOtherUserID);
                    }

                    objJSONResponse = objService.getChatPostsWithUser(mOtherUserID, null, 1);
                }

                if (objJSONResponse != null) {
                    for (int i = 0; i < objJSONResponse.length(); i++) {
                        arrResultItems.add(new ChatPostItem(objJSONResponse.optJSONObject(i)));
                    }

                    return true;
                } else {
                    return false;
                }

            } catch (Exception e) {
                Log.w("doInBackground", e.getMessage());
                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            int intPositionToSelect = -1;
            try {
                if (success) {
                    adapterLoadingItems = (ChatPostAdapter) ((HeaderViewListAdapter) mList.getAdapter()).getWrappedAdapter();

                    if (arrResultItems.size() > 0) {
                        intPositionToSelect = mList.getCount() + arrResultItems.size() - 1;
                    }
                    adapterLoadingItems.addAll(arrResultItems);
                    adapterLoadingItems.notifyDataSetChanged();

                    if (mLastViewDateTicks == null) {
                        mList.setLastPageReached(true);
                    }

                    mRefreshPostsHandler.postDelayed(mRefreshPostsRunnable, REFRESH_POSTS_TIMER);
                } else if (mIsFirstLoading) {
                    ((MyApplication) getApplication()).displayNoConnectivityMessage();
                }
            } catch (Exception e) {
                Log.w("onPostExecute", e.getMessage());
            }
            if (intPositionToSelect > 0) {
                mList.setSelection(intPositionToSelect - 1);
            }
            if (mIsFirstLoading) {
                mList.onLoadMoreFailed();
                mIsFirstLoading = false;
                mIsClickBlocked = false;
            }
        }
    }

    /**
     * Represents an asynchronous loading task.
     */

    public ChatPostAdapter adapterLoadingItems;

    private class LoadingMoreTask extends AsyncTask<Void, Void, Boolean> {
        ArrayList<ChatPostItem> arrResultItems = new ArrayList<ChatPostItem>();
        JSONArray objJSONResponse;

        @Override
        protected Boolean doInBackground(Void... params) {
            try {
                if (mRefreshPostsHandler != null) {
                    mRefreshPostsHandler.removeCallbacks(mRefreshPostsRunnable);
                }

                MyApplication myApp = (MyApplication) getApplication();
                ServiceInterface objService = new ServiceInterface(myApp.getUser());
                int intPageToLoad = mList.getCurrentPageNumber() + 1;

                if (myApp.isOnlineAndServerReachable()) {
                    if (mLastViewDateTicks == null) {
                        mLastViewDateTicks = objService.getChatRoomLastVerification(mOtherUserID);
                    }

                    objJSONResponse = objService.getChatPostsWithUser(mOtherUserID, mLastViewDateTicks, intPageToLoad);
                }

                if (objJSONResponse != null) {
                    for (int i = 0; i < objJSONResponse.length(); i++) {
                        arrResultItems.add(new ChatPostItem(objJSONResponse.optJSONObject(i)));
                    }

                    return true;
                } else {
                    return false;
                }

            } catch (Exception e) {
                Log.w("doInBackground", e.getMessage());
                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            int intPositionToSelect = -1;
            try {
                if (success) {
                    adapterLoadingItems = (ChatPostAdapter) ((HeaderViewListAdapter) mList.getAdapter()).getWrappedAdapter();

                    if (arrResultItems.size() < ServiceInterface.PAGE_SIZE_CHAT_POSTS) {
                        mList.setLastPageReached(true);
                    }
                    intPositionToSelect = arrResultItems.size();

                    adapterLoadingItems.addAllBefore(arrResultItems);
                    adapterLoadingItems.notifyDataSetChanged();

                    mRefreshPostsHandler.postDelayed(mRefreshPostsRunnable, REFRESH_POSTS_TIMER);
                } else {
                    ((MyApplication) getApplication()).displayNoConnectivityMessage();
                }
            } catch (Exception e) {
                Log.w("onPostExecute", e.getMessage());
            }
            if (success) {
                mList.onLoadMoreComplete();
            } else {
                mList.onLoadMoreFailed();
            }
            if (intPositionToSelect > 0) {
                mList.setSelection(intPositionToSelect - 1);
            }
        }
    }

    private class SendingTask extends AsyncTask<Void, Void, Boolean> {
        ArrayList<ChatPostItem> arrResultItems = new ArrayList<ChatPostItem>();

        JSONArray objJSONResponse = null;

        @Override
        protected Boolean doInBackground(Void... params) {
            MyApplication myApp = (MyApplication) getApplication();
            ServiceInterface objService = new ServiceInterface(myApp.getUser());

            String strMessage = mMessage.getText().toString();
            if (myApp.isOnlineAndServerReachable()) {
                if (strMessage.isEmpty()) {
                    mAudiofile = new File(mFileName);
                    objJSONResponse = objService.insertChatAudio(getApplicationContext(), mAudiofile, mOtherUserID);
                } else {
                    objJSONResponse = objService.insertChatPost(strMessage, mOtherUserID);
                }
            }
            if (objJSONResponse != null) {
                for (int i = 0; i < objJSONResponse.length(); i++) {
                    arrResultItems.add(new ChatPostItem(objJSONResponse.optJSONObject(i)));
                }

                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            try {
                if (success) {
                    ChatPostAdapter adapter = (ChatPostAdapter) ((HeaderViewListAdapter) mList.getAdapter()).getWrappedAdapter();
                    adapter.addAll(arrResultItems);
                    adapter.notifyDataSetChanged();

                    mRefreshPostsHandler.postDelayed(mRefreshPostsRunnable, REFRESH_POSTS_TIMER);
                    mMessage.setText("");

                    mAudiofile = new File(mFileName);
                    if (mAudiofile.exists())
                        mAudiofile.delete();

                } else {
                    ((MyApplication) getApplication()).displayNoConnectivityMessage();
                }
            } catch (Exception e) {
                Log.w("onPostExecute", e.getMessage());
            }

            if (arrResultItems.size() > 0) {
                mList.setSelection(mList.getCount() - 1);
            }

            mMessage.setEnabled(true);
            mIsClickBlocked = false;
        }
    }

    @Override
    public void stopTasks() {
        if (mRefreshPostsHandler != null) {
            mRefreshPostsHandler.removeCallbacks(mRefreshPostsRunnable);
        }
        if (mLoadingNewTask != null && mLoadingNewTask.getStatus() != Status.FINISHED) {
            mLoadingNewTask.cancel(true);
        }
        if (mLoadingMoreTask != null && mLoadingMoreTask.getStatus() != Status.FINISHED) {
            mLoadingMoreTask.cancel(true);
        }
        if (mSendingTask != null && mSendingTask.getStatus() != Status.FINISHED) {
            mSendingTask.cancel(true);
        }

        mHandler.removeCallbacks(mUpdateRecordTimeTask);
    }

    public void playVoiceMessage() {
        if (mp != null) {
            mp.reset();
            if (ChatPostAdapter.mPlayingHandler != null) {
                ChatPostAdapter.mPlayingHandler.removeCallbacks(ChatPostAdapter.mPlayingRunnable);
            }
        }

        String url = ((ChatPostItem) adapterLoadingItems.getItem(ChatPostAdapter.mPlayingPosition)).getAudioFile();
        try {
            mp = new MediaPlayer();
            mp.setDataSource(url);

            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    return false;
                }
            });
            mp.prepareAsync();

            ChatPostAdapter.mPlayingRunnable = new Runnable() {
                @Override
                public void run() {
                    if (ChatPostAdapter.mPlayingPosition != -1) {
                        long currentDuration = mp.getCurrentPosition();
                        int progress = (AudioUtilities.getProgressPercentage(currentDuration, mp.getDuration()));

                        ChatPostItem chatPostItem = (ChatPostItem) adapterLoadingItems.getItem(ChatPostAdapter.mPlayingPosition);

                        chatPostItem.mCurrentTime = AudioUtilities.milliSecondsToTimer(currentDuration);
                        chatPostItem.mTotalDuration = mp.getDuration();
                        chatPostItem.mSeekbarPosition = progress;

                        ChatPostAdapter.mPlayingHandler.postDelayed(this, PLAYING_HANDLER_POST_DELAYED);

                        adapterLoadingItems.notifyDataSetChanged();
                    }
                }
            };

            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(final MediaPlayer mp) {
                    mp.start();

                    ChatPostAdapter.mPlayingHandler = new Handler();
                    ChatPostAdapter.mPlayingHandler.post(ChatPostAdapter.mPlayingRunnable);
                }
            });

            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mediaPlayer) {

                    if (ChatPostAdapter.mPlayingHandler != null) {
                        ChatPostAdapter.mPlayingHandler.removeCallbacks(ChatPostAdapter.mPlayingRunnable);
                    }

                    ChatPostItem chatPostItem;
                    if (ChatPostAdapter.mPlayingPosition != -1) {
                        chatPostItem = (ChatPostItem) adapterLoadingItems.getItem(ChatPostAdapter.mPlayingPosition);

                        chatPostItem.mTotalDuration = 0;
                        chatPostItem.mSeekbarPosition = 0;
                        chatPostItem.mCurrentTime = "";
                        chatPostItem.mPlayingStatus = PlayingState.READY_TO_PLAY;
                    }

                    ChatPostAdapter.mPlayingPosition = -1;

                    mp.reset();
                    adapterLoadingItems.notifyDataSetChanged();
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onSeekBarChangeListener(int position, int seekPosition) {
        ChatPostItem chatPostItem = (ChatPostItem) adapterLoadingItems.getItem(position);

        mp.seekTo(seekPosition);

        chatPostItem.mSeekbarPosition = seekPosition;

        ChatPostAdapter.mPlayingHandler.postDelayed(ChatPostAdapter.mPlayingRunnable, 1000);
    }

    @Override
    public void onPlayPauseClickListener(int position) {
        if (ChatPostAdapter.mPlayingHandler != null) {
            ChatPostAdapter.mPlayingHandler.removeCallbacks(ChatPostAdapter.mPlayingRunnable);
        }

        if (ChatPostAdapter.mPlayingPosition != -1 && ChatPostAdapter.mPlayingPosition != position) {
            ChatPostItem previousChatPostItem = (ChatPostItem) adapterLoadingItems.getItem(ChatPostAdapter.mPlayingPosition);

            previousChatPostItem.mTotalDuration = 0;
            previousChatPostItem.mSeekbarPosition = 0;
            previousChatPostItem.mCurrentTime = "";
            previousChatPostItem.mPlayingStatus = PlayingState.READY_TO_PLAY;
        }

        ChatPostAdapter.mPlayingPosition = position;
        ChatPostItem chatPostItem = (ChatPostItem) adapterLoadingItems.getItem(position);

        if (chatPostItem.mPlayingStatus == PlayingState.READY_TO_PLAY) {
            chatPostItem.mPlayingStatus = PlayingState.PLAYING;
            playVoiceMessage();
        } else if (chatPostItem.mPlayingStatus == PlayingState.PLAYING) {
            if (mp.isPlaying()) {
                chatPostItem.mPlayingStatus = PlayingState.PAUSED;
                mp.pause();

            }
        } else {
            chatPostItem.mPlayingStatus = PlayingState.PLAYING;
            mp.start();
            ChatPostAdapter.mPlayingHandler.post(ChatPostAdapter.mPlayingRunnable);
        }
        adapterLoadingItems.notifyDataSetChanged();
    }

    //<///<editor-fold desc="Record audio methods">

    private void onRecord(boolean start) {
        if (start) {
            Calendar oCalendar = Calendar.getInstance();
            String audioFileName = String.format(Locale.getDefault(), RECORDED_FILE_NAME_PREFIX, oCalendar.getTimeInMillis());

            mFileName = Settings.getSharedCacheFolder(getApplicationContext()).getAbsolutePath();
            mFileName += "/" + audioFileName;

            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {
        vibrate();

        if (mRecorder != null) {
            mRecorder.reset();
            mRecorder.release();
        }
        mRecorder = null;

        MyApplication myApp = (MyApplication) getApplication();
        UserData user = myApp.getUser();
        int maxRecordingTimeInMillis = user.getSettings().getMaxChatVoiceMessageInSeconds() * 1000;

        mRecorder = new MediaRecorder();
        mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mRecorder.setMaxDuration(maxRecordingTimeInMillis);
        mRecorder.setOutputFile(mFileName);
        mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
            @Override
            public void onInfo(MediaRecorder mr, int what, int extra) {

                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    Log.i(LOG_TAG, "Maximum Duration Reached");
                    mRecorder.stop();
                    mMaxDurationReached = true;
                    mCountDownToast.cancel();
                    mHandler.removeCallbacks(mUpdateRecordTimeTask);
                }
            }
        });
        try {
            mRecorder.prepare();
            mRecorder.start();
            updateCurrentRecordingTime();
            blinkRecordingImage(true);
        } catch (IOException e) {
            Log.e(LOG_TAG, e.getMessage());
        }
    }

    private void cancelRecording() {
        if (mRecorder != null) {
            vibrate();
            try {
                mRecorder.release();
                mRecorder = null;
            } catch (Exception ex) {
                Log.i(LOG_TAG, ex.getMessage());
            }

            if (recImage != null) {
                blinkRecordingImage(false);
            }
            mStartTime = System.currentTimeMillis();
            mTimeInMilliseconds = 0L;
            mUpdatedTime = 0L;
            toastShown = false;

            mHandler.removeCallbacks(mUpdateRecordTimeTask);

            mAudiofile = new File(mFileName);

            if (mAudiofile.exists())
                mAudiofile.delete();
        }
    }


    private void stopRecording() {

        if (mRecorder != null) {
            vibrate();
            long currentRecordedTimeSec = Long.parseLong(recordTimeText.getText().toString().split(":")[1]);
            long currentRecordedTimeMin = Long.parseLong(recordTimeText.getText().toString().split(":")[0]);

            if (currentRecordedTimeMin >= 1) {
                currentRecordedTimeSec = 60;
            }

            mAudiofile = new File(mFileName);

            if (currentRecordedTimeSec < 1) {
                mRecorder.release();
                mRecorder = null;

                mHandler.removeCallbacks(mUpdateRecordTimeTask);

                mStartTime = System.currentTimeMillis();
                mTimeInMilliseconds = 0L;
                mUpdatedTime = 0L;
                toastShown = false;

                blinkRecordingImage(false);

                if (mAudiofile.exists())
                    mAudiofile.delete();

                return;
            }
            recordTimeText.setText("00:00");

            if (!mMaxDurationReached) {
                mCountDownToast.cancel();
                mRecorder.stop();
            }

            mRecorder.release();
            mRecorder = null;

            blinkRecordingImage(false);

            mStartTime = System.currentTimeMillis();
            mTimeInMilliseconds = 0L;
            mUpdatedTime = 0L;
            toastShown = false;

            mHandler.removeCallbacks(mUpdateRecordTimeTask);

            MediaMetadataRetriever oMediaData = new MediaMetadataRetriever();
            oMediaData.setDataSource(mAudiofile.getAbsolutePath());
            String milliDuration = oMediaData.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            int duration = 0;
            if (milliDuration != null) {
                duration = Integer.parseInt(milliDuration);
            }

            if (duration > 1000) {
                if (!mIsClickBlocked) {
                    mIsClickBlocked = true;

                    if (mSendingTask == null || mSendingTask.getStatus() == Status.FINISHED) {
                        mMessage.setEnabled(false);
                        mSendingTask = new SendingTask();
                        mSendingTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void) null);
                    } else {
                        mIsClickBlocked = false;
                    }
                }
            }
        }
    }

    private void vibrate() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            v.vibrate(200);
        } catch (Exception e) {
            Log.i(LOG_TAG, e.getMessage());
        }
    }

    public static int dp(float value) {
        return (int) Math.ceil(1 * value);
    }

    boolean toastShown = false;
    private Runnable mUpdateRecordTimeTask = new Runnable() {

        public void run() {

            try {
                mTimeInMilliseconds = (System.currentTimeMillis() - mStartTime);
                mUpdatedTime = mTimeSwapBuff + mTimeInMilliseconds;

                int secs = (int) (mUpdatedTime / 1000);
                int mins = secs / 60;
                secs = secs % 60;

                MyApplication myApp = (MyApplication) getApplication();
                UserData user = myApp.getUser();
                NetworkSettings settings = user.getSettings();

                int remainingTime = settings.getMaxChatVoiceMessageInSeconds() - secs;

                if (secs >= settings.getMaxChatVoiceMessageInSeconds()) {
                    recordTimeText.setText(String.format(Locale.getDefault(), "%02d:%02d", 0, settings.getMaxChatVoiceMessageInSeconds()));
                } else {
                    recordTimeText.setText(String.format(Locale.getDefault(), "%02d:%02d", mins, secs));
                }

                if (remainingTime < MAX_RECORDING_TOAST_WARNING) {
                    LayoutInflater mInflater = getLayoutInflater();
                    View myLayout = mInflater.inflate(R.layout.record_audio_custom_toast, (ViewGroup) findViewById(R.id.record_audio_custom_toast));

                    Toaster.makeLongToast(mCountDownToast, myLayout, getApplicationContext(), remainingTime);
                }

                mHandler.postDelayed(this, 0);
            } catch (Exception ex) {
                Log.i(LOG_TAG, ex.getMessage());
            }
        }
    };

    public void showHidePanelWriteMessage(boolean hide) {

        LinearLayout oLayout = (LinearLayout) findViewById(R.id.chat_posts_send_container);

        View recordPanel = findViewById(R.id.record_panel);
        ViewGroup.LayoutParams params = recordPanel.getLayoutParams();

        params.width = oLayout.getWidth();

        recordPanel.setLayoutParams(params);

        Animation animation;

        if (hide) {
            animation = new TranslateAnimation(oLayout.getWidth(), 0, 0, 0);
            animation.setDuration(300);
            animation.setFillAfter(true);

            record_panel.setVisibility(View.VISIBLE);
        } else {
            animation = new TranslateAnimation(0, oLayout.getWidth(), 0, 0);
            animation.setDuration(300);
            animation.setFillAfter(true);

            record_panel.setVisibility(View.GONE);
        }

        record_panel.startAnimation(animation);
    }

    public void blinkRecordingImage(boolean blink) {
        if (blink) {
            final Animation animation = new AlphaAnimation(1, 0);
            animation.setDuration(500);
            animation.setInterpolator(new LinearInterpolator());
            animation.setRepeatCount(Animation.INFINITE);
            animation.setRepeatMode(Animation.REVERSE);
            recImage.startAnimation(animation);
        } else {
            recImage.clearAnimation();
        }
    }

    public void updateCurrentRecordingTime() {
        mStartTime = System.currentTimeMillis();
        mHandler.postDelayed(mUpdateRecordTimeTask, 0);
    }

    private void configureRecordingObjects() {
        MyApplication myApp = (MyApplication) getApplication();
        UserData user = myApp.getUser();

        mCountDownToast = new Toast(getApplicationContext());
        recordTimeText = (TextView) findViewById(R.id.recording_time_text);
        slideText = findViewById(R.id.slideText);
        audioSendButton = (ImageButton) findViewById(R.id.chat_posts_record_button);
        audioSendButton.setVisibility(!user.getPermissions().getChatCanSendVoice() ? View.GONE : View.VISIBLE);
        recImage = (ImageView) findViewById(R.id.recording_rec_image);
        slideToCancel = (TextView) findViewById(R.id.slideToCancelTextView);
        slideToCancel.setText(getString(R.string.slide_to_cancel_message));

        record_panel = (FrameLayoutFixed) (findViewById(R.id.record_panel));

        audioSendButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) slideText.getLayoutParams();
                    params.leftMargin = dp(30);
                    slideText.setLayoutParams(params);
                    ViewProxy.setAlpha(slideText, 1);
                    startedDraggingX = -1;
                    showHidePanelWriteMessage(true);
                    onRecord(true);
                    audioSendButton.getParent()
                            .requestDisallowInterceptTouchEvent(true);
                    record_panel.setVisibility(View.VISIBLE);
                } else if (motionEvent.getAction() == MotionEvent.ACTION_UP
                        || motionEvent.getAction() == MotionEvent.ACTION_CANCEL) {
                    startedDraggingX = -1;
                    showHidePanelWriteMessage(false);
                    onRecord(false);
                } else if (motionEvent.getAction() == MotionEvent.ACTION_MOVE) {
                    float x = motionEvent.getX();
                    if (x < -distCanMove) {
                        showHidePanelWriteMessage(false);
                        cancelRecording();
                    }
                    x = x + ViewProxy.getX(audioSendButton);
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) slideText
                            .getLayoutParams();
                    if (startedDraggingX != -1) {
                        float dist = (x - startedDraggingX);
                        params.leftMargin = dp(30) + (int) dist;
                        slideText.setLayoutParams(params);
                        float alpha = 1.0f + dist / distCanMove;
                        if (alpha > 1) {
                            alpha = 1;
                        } else if (alpha < 0) {
                            alpha = 0;
                        }
                        ViewProxy.setAlpha(slideText, alpha);
                    }
                    if (x <= ViewProxy.getX(slideText) + slideText.getWidth()
                            + dp(30)) {
                        if (startedDraggingX == -1) {
                            startedDraggingX = x;
                            distCanMove = (record_panel.getMeasuredWidth()
                                    - slideText.getMeasuredWidth() - dp(48)) / 2.0f;
                            if (distCanMove <= 0) {
                                distCanMove = dp(80);
                            } else if (distCanMove > dp(80)) {
                                distCanMove = dp(80);
                            }
                        }
                    }
                    if (params.leftMargin > dp(30)) {
                        params.leftMargin = dp(30);
                        slideText.setLayoutParams(params);
                        ViewProxy.setAlpha(slideText, 1);
                        startedDraggingX = -1;
                    }
                }
                view.onTouchEvent(motionEvent);
                return true;
            }
        });
    }
    //</editor-fold>


}
