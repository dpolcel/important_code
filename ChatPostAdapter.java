package br.com.bee.android.model.list;

import br.com.bee.android.ChatActivity;
import br.com.bee.android.R;
import br.com.bee.android.model.MyApplication;
import br.com.bee.android.model.user.UserData;
import br.com.bee.android.util.AudioUtilities;
import br.com.bee.android.util.PlayingState;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public class ChatPostAdapter extends BaseAdapter {
    private static final String US_DATE_TIME = "M/d/yyyy - h:mm:ss a";
    private static final String STANDARD_DATE_TIME = "dd/MM/yyyy - HH:mm:ss";

    private Context context;
    private ArrayList<ChatPostItem> navDrawerItems;

    public static int mPlayingPosition = -1;
    public static Runnable mPlayingRunnable = null;
    public static Handler mPlayingHandler;

    public ChatPostAdapter(Context context, ArrayList<ChatPostItem> navDrawerItems) {
        this.context = context;
        this.navDrawerItems = navDrawerItems;
    }

    @Override
    public int getCount() {
        return navDrawerItems.size();
    }

    @Override
    public Object getItem(int position) {
        if (position < navDrawerItems.size()) {
            return navDrawerItems.get(position);
        } else {
            return null;
        }
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    public void addAllBefore(ArrayList<ChatPostItem> items) {
        this.navDrawerItems.addAll(0, items);

        if (mPlayingPosition != -1) {
            mPlayingPosition += items.size();
        }
    }

    public void addAll(ArrayList<ChatPostItem> items) {
        this.navDrawerItems.addAll(items);
    }

    public void removeAll() {
        this.navDrawerItems.clear();
    }

    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {

        View v = convertView;
        LayoutInflater mInflater = (LayoutInflater) this.context.getSystemService(Activity.LAYOUT_INFLATER_SERVICE);

        try {
            if (position >= 0 && position < navDrawerItems.size()) {
                final ChatPostItem item = navDrawerItems.get(position);
                if (item != null) {
                    UserData user = ((MyApplication) this.context.getApplicationContext()).getUser();
                    final ViewHolder holder;

                    if (v == null) {
                        holder = new ViewHolder();

                        v = mInflater.inflate(R.layout.list_item_chat, parent, false);

                        holder.Post = (TextView) v.findViewById(R.id.list_item_chat_post_text);
                        holder.CreationDatetime = (TextView) v.findViewById(R.id.list_item_chat_post_date);
                        holder.CurrentPlayingTime = (TextView) v.findViewById(R.id.lsit_item_chat_current_playing_time);
                        holder.CurrentPlayingProgress = (SeekBar) v.findViewById(R.id.list_item_chat_current_playing_seekbar);
                        holder.CurrentPlayingTotalDuration = (TextView) v.findViewById(R.id.list_item_chat_current_playing_total_duration);
                        holder.PlayButton = (ImageButton) v.findViewById(R.id.list_item_chat_play_button);
                        holder.MessageControls = (LinearLayout) v.findViewById(R.id.list_item_chat_layout_message_controls);
                        holder.MainContainer = (LinearLayout) v.findViewById(R.id.layout_main_container);
                        holder.ChatRightPost = (ImageView) v.findViewById(R.id.chat_right_post);
                        holder.ChatLeftPost = (ImageView) v.findViewById(R.id.chat_left_post);
                        holder.PlayerContainer = (RelativeLayout) v.findViewById(R.id.list_item_chat_layout_player_content);

                        v.setTag(holder);
                    } else {
                        holder = (ViewHolder) v.getTag();

                        holder.CurrentPlayingProgress.setProgress(item.mSeekbarPosition);
                        holder.CurrentPlayingProgress.setMax(100);
                        holder.CurrentPlayingTotalDuration.setText(item.mTotalDuration == 0 ? "" : AudioUtilities.milliSecondsToTimer(item.mTotalDuration));
                        holder.CurrentPlayingTime.setText(item.mCurrentTime);

                        if (item.mPlayingStatus == PlayingState.PLAYING) {
                            holder.PlayButton.setImageResource(R.drawable.ic_action_pause);
                            holder.CurrentPlayingProgress.setEnabled(true);
                        } else {
                            holder.PlayButton.setImageResource(R.drawable.ic_action_play);
                            holder.CurrentPlayingProgress.setEnabled(false);
                        }
                    }

                    holder.PlayButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View view) {
                            holder.CurrentPlayingProgress.setEnabled(item.mPlayingStatus != PlayingState.PLAYING);

                            mPlayPauseButtonListener.onPlayPauseClickListener(position);
                        }
                    });

                    showHideLayoutButtons(holder, item, user);

                    holder.CurrentPlayingProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

                        }

                        @Override
                        public void onStartTrackingTouch(SeekBar seekBar) {
                            ChatPostAdapter.mPlayingHandler.removeCallbacks(ChatPostAdapter.mPlayingRunnable);
                        }

                        @Override
                        public void onStopTrackingTouch(SeekBar seekBar) {
                            ChatPostAdapter.mPlayingHandler.removeCallbacks(ChatPostAdapter.mPlayingRunnable);

                            int seekPosition = AudioUtilities.progressToTimer(seekBar.getProgress(), item.mTotalDuration);

                            mSeekBarChangeListener.onSeekBarChangeListener(position, seekPosition);
                        }
                    });

                    try {
                        SimpleDateFormat originalFormat = new SimpleDateFormat(US_DATE_TIME, Locale.US);
                        SimpleDateFormat targetFormat = new SimpleDateFormat(STANDARD_DATE_TIME, Locale.US);
                        Date date = originalFormat.parse(item.getCreatedDateTime());

                        holder.CreationDatetime.setText(targetFormat.format(date));
                    } catch (Exception e) {
                        holder.CreationDatetime.setText(item.getCreatedDateTime());
                    }
                }

            }
        } catch (Exception e) {
            // TODO: handle exception
            Log.w("Adaptor getView", e.toString());
        }

        return v;
    }

    public void showHideLayoutButtons(ViewHolder holder, ChatPostItem item, UserData user) {
        LinearLayout.LayoutParams layout_content_all_items_params = new LinearLayout.LayoutParams(holder.MessageControls.getLayoutParams());
        final int CHAT_POST_MARGIN = 100;

        if (item.getUserID() == user.getID()) {

            final int sdk = android.os.Build.VERSION.SDK_INT;
            if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                holder.MessageControls.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.listview_item_chat_right_shape));
            } else {
                holder.MessageControls.setBackground(ContextCompat.getDrawable(context, R.drawable.listview_item_chat_right_shape));
            }

            holder.MainContainer.setGravity(Gravity.END);

            layout_content_all_items_params.setMargins(CHAT_POST_MARGIN, layout_content_all_items_params.topMargin, layout_content_all_items_params.rightMargin, layout_content_all_items_params.bottomMargin);
            holder.MessageControls.setLayoutParams(layout_content_all_items_params);

            holder.CreationDatetime.setGravity(Gravity.START);
            holder.ChatRightPost.setVisibility(View.VISIBLE);
            holder.ChatLeftPost.setVisibility(View.GONE);

        } else {
            final int sdk = android.os.Build.VERSION.SDK_INT;
            if (sdk < android.os.Build.VERSION_CODES.JELLY_BEAN) {
                holder.MessageControls.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.listview_item_chat_left_shape));
            } else {
                holder.MessageControls.setBackground(ContextCompat.getDrawable(context, R.drawable.listview_item_chat_left_shape));
            }

            holder.MainContainer.setGravity(Gravity.START);

            layout_content_all_items_params.setMargins(layout_content_all_items_params.leftMargin, layout_content_all_items_params.topMargin, CHAT_POST_MARGIN, layout_content_all_items_params.bottomMargin);
            holder.MessageControls.setLayoutParams(layout_content_all_items_params);

            holder.CreationDatetime.setGravity(Gravity.END);
            holder.ChatLeftPost.setVisibility(View.VISIBLE);
            holder.ChatRightPost.setVisibility(View.GONE);
        }

        if (item.getIsAudioPost()) {
            holder.Post.setVisibility(View.GONE);

            holder.PlayerContainer.setVisibility(View.VISIBLE);
        } else {
            holder.Post.setVisibility(View.VISIBLE);
            holder.PlayerContainer.setVisibility(View.GONE);
            holder.Post.setText(item.getPost());
        }
    }


    public static class ViewHolder {
        TextView CreationDatetime;
        TextView Post;
        TextView CurrentPlayingTime;
        SeekBar CurrentPlayingProgress;
        TextView CurrentPlayingTotalDuration;
        ImageButton PlayButton;
        LinearLayout MessageControls;
        ImageView ChatRightPost;
        ImageView ChatLeftPost;
        LinearLayout MainContainer;
        RelativeLayout PlayerContainer;
    }

    OnPlayPauseButtonClickListener mPlayPauseButtonListener;
    public interface OnPlayPauseButtonClickListener {
        void onPlayPauseClickListener(int position);
    }

    public void setPlayPauseButtonListener(OnPlayPauseButtonClickListener playPauseButtonListener) {
        this.mPlayPauseButtonListener = playPauseButtonListener;
    }


    OnSeekBarChangeListener mSeekBarChangeListener;
    public interface OnSeekBarChangeListener {
        void onSeekBarChangeListener(int position, int seekPosition);

    }

    public void setSeekBarChangeListener(OnSeekBarChangeListener seekBarChangeListener) {
        this.mSeekBarChangeListener = seekBarChangeListener;
    }
}
