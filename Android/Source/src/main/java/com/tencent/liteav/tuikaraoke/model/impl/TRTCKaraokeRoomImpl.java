package com.tencent.liteav.tuikaraoke.model.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.Gson;
import com.tencent.liteav.audio.TXAudioEffectManager;
import com.tencent.liteav.tuikaraoke.model.TRTCKaraokeRoom;
import com.tencent.liteav.tuikaraoke.model.TRTCKaraokeRoomCallback;
import com.tencent.liteav.tuikaraoke.model.TRTCKaraokeRoomDef;
import com.tencent.liteav.tuikaraoke.model.TRTCKaraokeRoomDelegate;
import com.tencent.liteav.tuikaraoke.model.impl.base.TRTCLogger;
import com.tencent.liteav.tuikaraoke.model.impl.base.TXCallback;
import com.tencent.liteav.tuikaraoke.model.impl.base.TXRoomInfo;
import com.tencent.liteav.tuikaraoke.model.impl.base.TXSeatInfo;
import com.tencent.liteav.tuikaraoke.model.impl.base.TXUserInfo;
import com.tencent.liteav.tuikaraoke.model.impl.base.TXUserListCallback;
import com.tencent.liteav.tuikaraoke.model.impl.room.ITXRoomServiceDelegate;
import com.tencent.liteav.tuikaraoke.model.impl.room.impl.TXRoomService;
import com.tencent.liteav.tuikaraoke.model.impl.trtc.TRTCKtvRoomService;
import com.tencent.liteav.tuikaraoke.model.impl.trtc.TRTCKtvRoomServiceDelegate;
import com.tencent.liteav.tuikaraoke.model.KaraokeSEIJsonData;
import com.tencent.rtmp.ui.TXCloudVideoView;
import com.tencent.trtc.TRTCCloudDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TRTCKaraokeRoomImpl extends TRTCKaraokeRoom implements ITXRoomServiceDelegate, TRTCKtvRoomServiceDelegate {
    private static final String TAG = "TRTCKaraokeRoomImpl";

    private static TRTCKaraokeRoomImpl     sInstance;
    private final  Context                 mContext;
    private        TRTCKaraokeRoomDelegate mDelegate;
    // 所有调用都切到主线程使用，保证内部多线程安全问题
    private        Handler                 mMainHandler;
    // 外部可指定的回调线程
    private        Handler                 mDelegateHandler;
    private        int                     mSdkAppId;
    private        String                  mUserId;
    private        String                  mUserSig;

    // 主播列表
    private Set<String>                            mAnchorList;
    // 已抛出的观众列表
    private Set<String>                            mAudienceList;
    private List<TRTCKaraokeRoomDef.SeatInfo>      mSeatInfoList;
    private TRTCKaraokeRoomCallback.ActionCallback mEnterSeatCallback;
    private TRTCKaraokeRoomCallback.ActionCallback mLeaveSeatCallback;
    private TRTCKaraokeRoomCallback.ActionCallback mPickSeatCallback;
    private TRTCKaraokeRoomCallback.ActionCallback mKickSeatCallback;
    private int                                    mTakeSeatIndex;

    //歌曲管理
    private TXAudioEffectManager mAudioEffectManager;
    private MusicListener        mMusicPlayListenr;
    private int                  mOriginId    = -1; //原唱Id
    private int                  mAccompanyId = -1; //伴奏Id
    private String               mCurPerformId;
    private boolean              mIsOriginReady;
    private boolean              mIsAccompanyReady;

    private static final int TYPE_ORIGIN    = 0; //原唱
    private static final int TYPE_ACCOMPANY = 1; //伴奏

    public static synchronized TRTCKaraokeRoom sharedInstance(Context context) {
        if (sInstance == null) {
            sInstance = new TRTCKaraokeRoomImpl(context.getApplicationContext());
        }
        return sInstance;
    }

    public static synchronized void destroySharedInstance() {
        if (sInstance != null) {
            sInstance.destroy();
            sInstance = null;
        }
    }

    private void destroy() {
        TXRoomService.getInstance().destroy();
    }

    private TRTCKaraokeRoomImpl(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());
        mDelegateHandler = new Handler(Looper.getMainLooper());
        mSeatInfoList = new ArrayList<>();
        mAnchorList = new HashSet<>();
        mAudienceList = new HashSet<>();
        mTakeSeatIndex = -1;
        TRTCKtvRoomService.getInstance().setDelegate(this);
        TRTCKtvRoomService.getInstance().init(context);
        TXRoomService.getInstance().init(context);
        TXRoomService.getInstance().setDelegate(this);
        mAudioEffectManager = getAudioEffectManager();
    }

    private void clearList() {
        mSeatInfoList.clear();
        mAnchorList.clear();
        mAudienceList.clear();
    }

    private void runOnMainThread(Runnable runnable) {
        Handler handler = mMainHandler;
        if (handler != null) {
            if (handler.getLooper() == Looper.myLooper()) {
                runnable.run();
            } else {
                handler.post(runnable);
            }
        } else {
            runnable.run();
        }
    }

    private void runOnDelegateThread(Runnable runnable) {
        Handler handler = mDelegateHandler;
        if (handler != null) {
            if (handler.getLooper() == Looper.myLooper()) {
                runnable.run();
            } else {
                handler.post(runnable);
            }
        } else {
            runnable.run();
        }
    }

    @Override
    public void setDelegate(TRTCKaraokeRoomDelegate delegate) {
        mDelegate = delegate;
    }

    @Override
    public void setDelegateHandler(Handler handler) {
        mDelegateHandler = handler;
    }

    @Override
    public void login(final int sdkAppId, final String userId, final String userSig, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start login, sdkAppId:" + sdkAppId + " userId:" + userId + " sign is empty:" + TextUtils.isEmpty(userSig));
                if (sdkAppId == 0 || TextUtils.isEmpty(userId) || TextUtils.isEmpty(userSig)) {
                    TRTCLogger.e(TAG, "start login fail. params invalid.");
                    if (callback != null) {
                        callback.onCallback(-1, "登录失败，参数有误");
                    }
                    return;
                }
                mSdkAppId = sdkAppId;
                mUserId = userId;
                mUserSig = userSig;
                TRTCLogger.i(TAG, "start login room service");
                TXRoomService.getInstance().login(sdkAppId, userId, userSig, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void logout(final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start logout");
                mSdkAppId = 0;
                mUserId = "";
                mUserSig = "";
                TRTCLogger.i(TAG, "start logout room service");
                TXRoomService.getInstance().logout(new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "logout room service finish, code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void setSelfProfile(final String userName, final String avatarURL, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "set profile, user name:" + userName + " avatar url:" + avatarURL);
                TXRoomService.getInstance().setSelfProfile(userName, avatarURL, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "set profile finish, code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void createRoom(final int roomId, final TRTCKaraokeRoomDef.RoomParam roomParam, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "create room, room id:" + roomId + " info:" + roomParam);
                if (roomId == 0) {
                    TRTCLogger.e(TAG, "create room fail. params invalid");
                    return;
                }

                final String strRoomId = String.valueOf(roomId);

                clearList();

                final String           roomName       = (roomParam == null ? "" : roomParam.roomName);
                final String           roomCover      = (roomParam == null ? "" : roomParam.coverUrl);
                final boolean          isNeedRequest  = (roomParam != null && roomParam.needRequest);
                final int              seatCount      = (roomParam == null ? 8 : roomParam.seatCount);
                final List<TXSeatInfo> txSeatInfoList = new ArrayList<>();
                if (roomParam != null && roomParam.seatInfoList != null) {
                    for (TRTCKaraokeRoomDef.SeatInfo seatInfo : roomParam.seatInfoList) {
                        TXSeatInfo item = new TXSeatInfo();
                        item.status = seatInfo.status;
                        item.mute = seatInfo.mute;
                        item.user = seatInfo.userId;
                        txSeatInfoList.add(item);
                        mSeatInfoList.add(seatInfo);
                    }
                } else {
                    for (int i = 0; i < seatCount; i++) {
                        txSeatInfoList.add(new TXSeatInfo());
                        mSeatInfoList.add(new TRTCKaraokeRoomDef.SeatInfo());
                    }
                }
                // 创建房间
                TXRoomService.getInstance().createRoom(strRoomId, roomName, roomCover, isNeedRequest, txSeatInfoList, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "create room in service, code:" + code + " msg:" + msg);
                        if (code == 0) {
                            enterTRTCRoomInner(strRoomId, mUserId, mUserSig, TRTCCloudDef.TRTCRoleAnchor, callback);
                            return;
                        } else {
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (mDelegate != null) {
                                        mDelegate.onError(code, msg);
                                    }
                                }
                            });
                        }
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void destroyRoom(final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start destroy room.");
                // TRTC 房间退房结果不关心
                TRTCLogger.i(TAG, "start exit trtc room.");
                TRTCKtvRoomService.getInstance().exitRoom(new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "exit trtc room finish, code:" + code + " msg:" + msg);
                        if (code != 0) {
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (mDelegate != null) {
                                        mDelegate.onError(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });

                TRTCLogger.i(TAG, "start destroy room service.");
                TXRoomService.getInstance().destroyRoom(new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "destroy room finish, code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });

                // 恢复设定
                clearList();
            }
        });
    }


    @Override
    public void enterRoom(final int roomId, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                // 恢复设定
                clearList();
                String strRoomId = String.valueOf(roomId);
                TRTCLogger.i(TAG, "start enter room, room id:" + roomId);
                enterTRTCRoomInner(strRoomId, mUserId, mUserSig, TRTCCloudDef.TRTCRoleAudience, new TRTCKaraokeRoomCallback.ActionCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "trtc enter room finish, room id:" + roomId + " code:" + code + " msg:" + msg);
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
                TXRoomService.getInstance().enterRoom(strRoomId, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        TRTCLogger.i(TAG, "enter room service finish, room id:" + roomId + " code:" + code + " msg:" + msg);
                        runOnMainThread(new Runnable() {
                            @Override
                            public void run() {
                                if (code != 0) {
                                    runOnDelegateThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (mDelegate != null) {
                                                mDelegate.onError(code, msg);
                                            }
                                        }
                                    });
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void exitRoom(final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "start exit room.");
                // 退房的时候需要判断主播是否在座位，如果是麦上主播，需要先清空座位列表
                if (isOnSeat(mUserId)) {
                    leaveSeat(new TRTCKaraokeRoomCallback.ActionCallback() {
                        @Override
                        public void onCallback(int code, String msg) {
                            exitRoomInternal(callback);
                        }
                    });
                } else {
                    exitRoomInternal(callback);
                }
            }
        });
    }

    private void exitRoomInternal(final TRTCKaraokeRoomCallback.ActionCallback callback) {
        TRTCKtvRoomService.getInstance().exitRoom(new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                if (code != 0) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mDelegate != null) {
                                mDelegate.onError(code, msg);
                            }
                        }
                    });
                }
            }
        });
        TRTCLogger.i(TAG, "start exit room service.");
        TXRoomService.getInstance().exitRoom(new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                TRTCLogger.i(TAG, "exit room finish, code:" + code + " msg:" + msg);
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCallback(code, msg);
                        }
                    }
                });
            }
        });
        clearList();
    }

    private boolean isOnSeat(String userId) {
        // 判断某个userid 是不是在座位上
        if (mSeatInfoList == null) {
            return false;
        }
        for (TRTCKaraokeRoomDef.SeatInfo seatInfo : mSeatInfoList) {
            if (userId != null && userId.equals(seatInfo.userId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void getUserInfoList(final List<String> userIdList, final TRTCKaraokeRoomCallback.UserListCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (userIdList == null) {
                    getAudienceList(callback);
                    return;
                }
                TXRoomService.getInstance().getUserInfo(userIdList, new TXUserListCallback() {
                    @Override
                    public void onCallback(final int code, final String msg, final List<TXUserInfo> list) {
                        TRTCLogger.i(TAG, "get audience list finish, code:" + code + " msg:" + msg + " list:" + (list != null ? list.size() : 0));
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    List<TRTCKaraokeRoomDef.UserInfo> userList = new ArrayList<>();
                                    if (list != null) {
                                        for (TXUserInfo info : list) {
                                            TRTCKaraokeRoomDef.UserInfo trtcUserInfo = new TRTCKaraokeRoomDef.UserInfo();
                                            trtcUserInfo.userId = info.userId;
                                            trtcUserInfo.userAvatar = info.avatarURL;
                                            trtcUserInfo.userName = info.userName;
                                            userList.add(trtcUserInfo);
                                            TRTCLogger.i(TAG, "info:" + info);
                                        }
                                    }
                                    callback.onCallback(code, msg, userList);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void getAudienceList(final TRTCKaraokeRoomCallback.UserListCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TXRoomService.getInstance().getAudienceList(new TXUserListCallback() {
                    @Override
                    public void onCallback(final int code, final String msg, final List<TXUserInfo> list) {
                        TRTCLogger.i(TAG, "get audience list finish, code:" + code + " msg:" + msg + " list:" + (list != null ? list.size() : 0));
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    List<TRTCKaraokeRoomDef.UserInfo> userList = new ArrayList<>();
                                    if (list != null) {
                                        for (TXUserInfo info : list) {
                                            TRTCKaraokeRoomDef.UserInfo trtcUserInfo = new TRTCKaraokeRoomDef.UserInfo();
                                            trtcUserInfo.userId = info.userId;
                                            trtcUserInfo.userAvatar = info.avatarURL;
                                            trtcUserInfo.userName = info.userName;
                                            userList.add(trtcUserInfo);
                                            TRTCLogger.i(TAG, "info:" + info);
                                        }
                                    }
                                    callback.onCallback(code, msg, userList);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void enterSeat(final int seatIndex, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "enterSeat " + seatIndex);
                if (isOnSeat(mUserId)) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onCallback(-1, "you are already in the seat");
                            }
                        }
                    });
                    return;
                }
                mEnterSeatCallback = callback;
                TXRoomService.getInstance().takeSeat(seatIndex, new TXCallback() {
                    @Override
                    public void onCallback(int code, String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mEnterSeatCallback = null;
                            mTakeSeatIndex = -1;
                            if (callback != null) {
                                callback.onCallback(code, msg);
                            }
                        } else {
                            TRTCLogger.i(TAG, "take seat callback success, and wait attrs changed.");
                        }
                    }
                });
            }
        });
    }

    @Override
    public void leaveSeat(final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "leaveSeat " + mTakeSeatIndex);
                if (mTakeSeatIndex == -1) {
                    //已经不再座位上了
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onCallback(-1, "you are not in the seat");
                            }
                        }
                    });
                    return;
                }
                mLeaveSeatCallback = callback;
                TXRoomService.getInstance().leaveSeat(mTakeSeatIndex, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mLeaveSeatCallback = null;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) {
                                        callback.onCallback(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override
    public void pickSeat(final int seatIndex, final String userId, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                //判断该用户是否已经在麦上
                TRTCLogger.i(TAG, "pickSeat " + seatIndex);
                if (isOnSeat(userId)) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (callback != null) {
                                callback.onCallback(-1, "该用户已经是麦上主播了");
                            }
                        }
                    });
                    return;
                }
                mPickSeatCallback = callback;
                TXRoomService.getInstance().pickSeat(seatIndex, userId, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mPickSeatCallback = null;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) {
                                        callback.onCallback(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override
    public void kickSeat(final int index, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "kickSeat " + index);
                mKickSeatCallback = callback;
                TXRoomService.getInstance().kickSeat(index, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        if (code != 0) {
                            //出错了，恢复callback
                            mKickSeatCallback = null;
                            runOnDelegateThread(new Runnable() {
                                @Override
                                public void run() {
                                    if (callback != null) {
                                        callback.onCallback(code, msg);
                                    }
                                }
                            });
                        }
                    }
                });
            }
        });
    }

    @Override
    public void muteSeat(final int seatIndex, final boolean isMute, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "muteSeat " + seatIndex + " " + isMute);
                TXRoomService.getInstance().muteSeat(seatIndex, isMute, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void closeSeat(final int seatIndex, final boolean isClose, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "closeSeat " + seatIndex + " " + isClose);
                TXRoomService.getInstance().closeSeat(seatIndex, isClose, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void startMicrophone() {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().startMicrophone();
            }
        });
    }

    @Override
    public void stopMicrophone() {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().stopMicrophone();
            }
        });
    }

    @Override
    public void setAudioQuality(final int quality) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().setAudioQuality(quality);
            }
        });
    }

    @Override
    public void setVoiceEarMonitorEnable(final boolean enable) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().enableAudioEarMonitoring(enable);
            }
        });
    }

    /**
     * 静音本地
     * <p>
     * 直接调用 TRTC 设置：TXTRTCLiveRoom.muteLocalAudio
     *
     * @param mute
     */
    @Override
    public void muteLocalAudio(final boolean mute) {
        TRTCLogger.i(TAG, "mute local audio, mute:" + mute);
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().muteLocalAudio(mute);
            }
        });
    }

    @Override
    public void setSpeaker(final boolean useSpeaker) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().setSpeaker(useSpeaker);
            }
        });
    }

    @Override
    public void setAudioCaptureVolume(final int volume) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().setAudioCaptureVolume(volume);
            }
        });
    }

    @Override
    public void setAudioPlayoutVolume(final int volume) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCKtvRoomService.getInstance().setAudioPlayoutVolume(volume);
            }
        });
    }

    /**
     * 静音音频
     *
     * @param userId
     * @param mute
     */
    @Override
    public void muteRemoteAudio(final String userId, final boolean mute) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "mute trtc audio, user id:" + userId);
                TRTCKtvRoomService.getInstance().muteRemoteAudio(userId, mute);
            }
        });
    }

    /**
     * 静音所有音频
     *
     * @param mute
     */
    @Override
    public void muteAllRemoteAudio(final boolean mute) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "mute all trtc remote audio success, mute:" + mute);
                TRTCKtvRoomService.getInstance().muteAllRemoteAudio(mute);
            }
        });
    }


    @Override
    public TXAudioEffectManager getAudioEffectManager() {
        return TRTCKtvRoomService.getInstance().getAudioEffectManager();
    }

    @Override
    public void sendRoomTextMsg(final String message, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "sendRoomTextMsg");
                TXRoomService.getInstance().sendRoomTextMsg(message, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void sendRoomCustomMsg(final String cmd, final String message, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "sendRoomCustomMsg");
                TXRoomService.getInstance().sendRoomCustomMsg(cmd, message, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public String sendInvitation(final String cmd, final String userId, final String content, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        TRTCLogger.i(TAG, "sendInvitation to " + userId + " cmd:" + cmd + " content:" + content);
        return TXRoomService.getInstance().sendInvitation(cmd, userId, content, new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCallback(code, msg);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void acceptInvitation(final String id, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "acceptInvitation " + id);
                TXRoomService.getInstance().acceptInvitation(id, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void rejectInvitation(final String id, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "rejectInvitation " + id);
                TXRoomService.getInstance().rejectInvitation(id, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    public void cancelInvitation(final String id, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                TRTCLogger.i(TAG, "cancelInvitation " + id);
                TXRoomService.getInstance().cancelInvitation(id, new TXCallback() {
                    @Override
                    public void onCallback(final int code, final String msg) {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (callback != null) {
                                    callback.onCallback(code, msg);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    private void enterTRTCRoomInner(final String roomId, final String userId, final String userSig, final int role, final TRTCKaraokeRoomCallback.ActionCallback callback) {
        // 进入 TRTC 房间
        TRTCLogger.i(TAG, "enter trtc room.");
        TRTCKtvRoomService.getInstance().enterRoom(mSdkAppId, roomId, userId, userSig, role, new TXCallback() {
            @Override
            public void onCallback(final int code, final String msg) {
                TRTCLogger.i(TAG, "enter trtc room finish, code:" + code + " msg:" + msg);
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onCallback(code, msg);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onRoomDestroy(final String roomId) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                exitRoom(null);
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mDelegate != null) {
                            mDelegate.onRoomDestroy(roomId);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onRoomRecvRoomTextMsg(final String roomId, final String message, final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCKaraokeRoomDef.UserInfo throwUser = new TRTCKaraokeRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onRecvRoomTextMsg(message, throwUser);
                }
            }
        });
    }

    @Override
    public void onRoomRecvRoomCustomMsg(final String roomId, final String cmd, final String message, final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCKaraokeRoomDef.UserInfo throwUser = new TRTCKaraokeRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onRecvRoomCustomMsg(cmd, message, throwUser);
                }
            }
        });
    }

    @Override
    public void onRoomInfoChange(final TXRoomInfo tXRoomInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                TRTCKaraokeRoomDef.RoomInfo roomInfo = new TRTCKaraokeRoomDef.RoomInfo();
                roomInfo.roomName = tXRoomInfo.roomName;
                int translateRoomId = 0;
                try {
                    translateRoomId = Integer.parseInt(tXRoomInfo.roomId);
                } catch (NumberFormatException e) {
                    TRTCLogger.e(TAG, e.getMessage());
                }
                roomInfo.roomId = translateRoomId;
                roomInfo.ownerId = tXRoomInfo.ownerId;
                roomInfo.ownerName = tXRoomInfo.ownerName;
                roomInfo.coverUrl = tXRoomInfo.cover;
                roomInfo.memberCount = tXRoomInfo.memberCount;
                roomInfo.needRequest = (tXRoomInfo.needRequest == 1);
                if (mDelegate != null) {
                    mDelegate.onRoomInfoChange(roomInfo);
                }
            }
        });
    }

    @Override
    public void onSeatInfoListChange(final List<TXSeatInfo> tXSeatInfoList) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                List<TRTCKaraokeRoomDef.SeatInfo> seatInfoList = new ArrayList<>();
                for (TXSeatInfo seatInfo : tXSeatInfoList) {
                    TRTCKaraokeRoomDef.SeatInfo info = new TRTCKaraokeRoomDef.SeatInfo();
                    info.userId = seatInfo.user;
                    info.mute = seatInfo.mute;
                    info.status = seatInfo.status;
                    seatInfoList.add(info);
                }
                mSeatInfoList = seatInfoList;
                if (mDelegate != null) {
                    mDelegate.onSeatListChange(seatInfoList);
                }
            }
        });
    }

    @Override
    public void onRoomAudienceEnter(final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCKaraokeRoomDef.UserInfo throwUser = new TRTCKaraokeRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onAudienceEnter(throwUser);
                }
            }
        });
    }

    @Override
    public void onRoomAudienceLeave(final TXUserInfo userInfo) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    TRTCKaraokeRoomDef.UserInfo throwUser = new TRTCKaraokeRoomDef.UserInfo();
                    throwUser.userId = userInfo.userId;
                    throwUser.userName = userInfo.userName;
                    throwUser.userAvatar = userInfo.avatarURL;
                    mDelegate.onAudienceExit(throwUser);
                }
            }
        });
    }

    @Override
    public void onSeatTake(final int index, final TXUserInfo userInfo) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (userInfo.userId.equals(mUserId)) {
                    //是自己上线了, 切换角色
                    mTakeSeatIndex = index;
                    TRTCKtvRoomService.getInstance().switchToAnchor();
                    boolean mute = mSeatInfoList.get(index).mute;
                    TRTCKtvRoomService.getInstance().muteLocalAudio(mute);
                    if (!mute) {
                        mDelegate.onUserMicrophoneMute(userInfo.userId, false);
                    }
                }
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mDelegate != null) {
                            TRTCKaraokeRoomDef.UserInfo info = new TRTCKaraokeRoomDef.UserInfo();
                            info.userId = userInfo.userId;
                            info.userAvatar = userInfo.avatarURL;
                            info.userName = userInfo.userName;
                            mDelegate.onAnchorEnterSeat(index, info);
                        }
                        if (mPickSeatCallback != null) {
                            mPickSeatCallback.onCallback(0, "pick seat success");
                            mPickSeatCallback = null;
                        }
                    }
                });
                if (userInfo.userId.equals(mUserId)) {
                    //在回调出去
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mEnterSeatCallback != null) {
                                mEnterSeatCallback.onCallback(0, "enter seat success");
                                mEnterSeatCallback = null;
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onSeatClose(final int index, final boolean isClose) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (mTakeSeatIndex == index && isClose) {
                    TRTCKtvRoomService.getInstance().switchToAudience();
                    mTakeSeatIndex = -1;
                }
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mDelegate != null) {
                            mDelegate.onSeatClose(index, isClose);
                        }
                    }
                });
            }
        });
    }

    @Override
    public void onSeatLeave(final int index, final TXUserInfo userInfo) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (userInfo.userId.equals(mUserId)) {
                    //自己下线了~
                    mTakeSeatIndex = -1;
                    TRTCKtvRoomService.getInstance().switchToAudience();
                }
                runOnDelegateThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mDelegate != null) {
                            TRTCKaraokeRoomDef.UserInfo info = new TRTCKaraokeRoomDef.UserInfo();
                            info.userId = userInfo.userId;
                            info.userAvatar = userInfo.avatarURL;
                            info.userName = userInfo.userName;
                            mDelegate.onAnchorLeaveSeat(index, info);
                        }
                        if (mKickSeatCallback != null) {
                            mKickSeatCallback.onCallback(0, "kick seat success");
                            mKickSeatCallback = null;
                        }
                    }
                });
                if (userInfo.userId.equals(mUserId)) {
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mLeaveSeatCallback != null) {
                                mLeaveSeatCallback.onCallback(0, "enter seat success");
                                mLeaveSeatCallback = null;
                            }
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onSeatMute(final int index, final boolean mute) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onSeatMute(index, mute);
                }
            }
        });
    }

    @Override
    public void onReceiveNewInvitation(final String id, final String inviter, final String cmd, final String content) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onReceiveNewInvitation(id, inviter, cmd, content);
                }
            }
        });
    }

    @Override
    public void onInviteeAccepted(final String id, final String invitee) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onInviteeAccepted(id, invitee);
                }
            }
        });
    }

    @Override
    public void onInviteeRejected(final String id, final String invitee) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onInviteeRejected(id, invitee);
                }
            }
        });
    }

    @Override
    public void onInvitationCancelled(final String id, final String inviter) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onInvitationCancelled(id, inviter);
                }
            }
        });
    }

    @Override
    public void onTRTCAnchorEnter(String userId) {
        mAnchorList.add(userId);
    }

    @Override
    public void onTRTCAnchorExit(String userId) {
        if (TXRoomService.getInstance().isOwner()) {
            // 主播是房主
            if (mSeatInfoList != null) {
                int kickSeatIndex = -1;
                for (int i = 0; i < mSeatInfoList.size(); i++) {
                    if (userId.equals(mSeatInfoList.get(i).userId)) {
                        kickSeatIndex = i;
                        break;
                    }
                }
                if (kickSeatIndex != -1) {
                    kickSeat(kickSeatIndex, null);
                }
            }
        }
        mAnchorList.remove(userId);
    }

    @Override
    public void onTRTCVideoAvailable(final String userId, final boolean available) {
        Log.d(TAG, "onTRTCVideoAvailable: userId = " + userId + " , available = " + available);
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (available) {
                    startRemoteView(userId, null);
                } else {
                    stopRemoteView(userId);
                }
            }
        });
    }

    @Override
    public void onTRTCAudioAvailable(final String userId, final boolean available) {
        runOnMainThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onUserMicrophoneMute(userId, !available);
                }
            }
        });
    }

    @Override
    public void onError(final int errorCode, final String errorMsg) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onError(errorCode, errorMsg);
                }
            }
        });
    }

    @Override
    public void onNetworkQuality(final TRTCCloudDef.TRTCQuality trtcQuality, final ArrayList<TRTCCloudDef.TRTCQuality> arrayList) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onNetworkQuality(trtcQuality, arrayList);
                }
            }
        });
    }

    @Override
    public void onUserVoiceVolume(final ArrayList<TRTCCloudDef.TRTCVolumeInfo> userVolumes, final int totalVolume) {
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null && userVolumes != null) {
                    mDelegate.onUserVolumeUpdate(userVolumes, totalVolume);
                }
            }
        });
    }


    @Override
    public void startPlayMusic(int musicID, String originalUrl, String accompanyUrl) {
        mCurPerformId = String.valueOf(musicID);
        mOriginId = 0;
        mAccompanyId = 1;
        enableBlackStream(true);
        mMusicPlayListenr = new MusicListener();
        //原唱
        final TXAudioEffectManager.AudioMusicParam audioMusicParam = new TXAudioEffectManager.AudioMusicParam(mOriginId, originalUrl);
        audioMusicParam.publish = true; //上行
        mAudioEffectManager.startPlayMusic(audioMusicParam);
        mAudioEffectManager.setMusicObserver(mOriginId, mMusicPlayListenr);
        //伴奏
        final TXAudioEffectManager.AudioMusicParam audioMusicParam2 = new TXAudioEffectManager.AudioMusicParam(mAccompanyId, accompanyUrl);
        audioMusicParam2.publish = true; //上行
        mAudioEffectManager.startPlayMusic(audioMusicParam2);
        mAudioEffectManager.setMusicObserver(mAccompanyId, mMusicPlayListenr);
    }

    public void setMusicVolume(int id, int volume) {
        if (volume < 0) {
            volume = 0;
        }
        if (volume > 100) {
            volume = 100;
        }
        mAudioEffectManager.setMusicPlayoutVolume(id, volume);
        mAudioEffectManager.setMusicPublishVolume(id, volume);
    }

    @Override
    public void switchToOriginalVolume(boolean isOriginal) {
        //如果是原唱,原唱音量为100,伴奏音量为0;
        //反之,如果是伴奏,伴奏音量为100,原唱为0;
        if (isOriginal) {
            setMusicVolume(mOriginId, 100);
            setMusicVolume(mAccompanyId, 0);
        } else {
            setMusicVolume(mOriginId, 0);
            setMusicVolume(mAccompanyId, 100);
        }
    }

    @Override
    public void stopPlayMusic() {
        enableBlackStream(false);
        mAudioEffectManager.stopPlayMusic(mOriginId);
        mAudioEffectManager.stopPlayMusic(mAccompanyId);
        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null) {
                    mDelegate.onMusicCompletePlaying(mCurPerformId);
                }
            }
        });
    }

    @Override
    public void pausePlayMusic() {
        mAudioEffectManager.pausePlayMusic(mOriginId);
        mAudioEffectManager.pausePlayMusic(mAccompanyId);
    }

    @Override
    public void resumePlayMusic() {
        mAudioEffectManager.resumePlayMusic(mOriginId);
        mAudioEffectManager.resumePlayMusic(mAccompanyId);
    }

    private class MusicListener implements TXAudioEffectManager.TXMusicPlayObserver {

        @Override
        public void onStart(int id, int errCode) {
            Log.d(TAG, "onMusicPlay Start: id = " + id + " , errCode = " + errCode);
            if (id == TYPE_ORIGIN) {
                mIsOriginReady = true;
            } else if (id == TYPE_ACCOMPANY) {
                mIsAccompanyReady = true;
            }
            //原唱和伴奏都准备好后才去回调
            if (!mIsOriginReady || !mIsAccompanyReady) {
                return;
            }
            runOnMainThread(new Runnable() {
                @Override
                public void run() {
                    if (mDelegate != null) {
                        mDelegate.onMusicPrepareToPlay(mCurPerformId);
                    }
                }
            });
        }

        @Override
        public void onPlayProgress(final int id, final long curPtsMS, final long durationMS) {
            //只根据一个进度去同步歌词
            if (id == TYPE_ORIGIN) {
                sendSEIMsg(mCurPerformId, curPtsMS, durationMS);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        runOnDelegateThread(new Runnable() {
                            @Override
                            public void run() {
                                if (mDelegate != null) {
                                    mDelegate.onMusicProgressUpdate(mCurPerformId, curPtsMS, durationMS);
                                }
                            }

                        });
                    }
                });
            }
        }

        @Override
        public void onComplete(final int id, final int errCode) {
            Log.d(TAG, "onMusicPlayComplete id " + id + " , status = " + errCode);
            //原唱和伴奏都准备好后才去回调完成
            if (id == TYPE_ORIGIN) {
                mIsOriginReady = false;
            } else if (id == TYPE_ACCOMPANY) {
                mIsAccompanyReady = false;
            }

            if (mIsOriginReady || mIsAccompanyReady) {
                return;
            }

            mMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    enableBlackStream(false);
                    runOnDelegateThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mDelegate != null) {
                                mDelegate.onMusicCompletePlaying(mCurPerformId);
                            }
                        }
                    });
                }
            });
        }
    }

    public void sendSEIMsg(String musicId, long curTime, long duration) {
        KaraokeSEIJsonData jsonData = new KaraokeSEIJsonData();
        jsonData.setCurrentTime(curTime);
        jsonData.setMusicId(musicId);
        jsonData.setTotalTime(duration);
        Gson   gson = new Gson();
        String data = gson.toJson(jsonData);
        TRTCKtvRoomService.getInstance().sendSEIMsg(data.getBytes(), 1);
    }

    public void enableBlackStream(boolean enable) {
        TRTCKtvRoomService.getInstance().enableBlackStream(enable);
    }

    @Override
    public void onRecvSEIMsg(final String userId, final byte[] data) {
        Gson               gson      = new Gson();
        String             result    = new String(data);
        KaraokeSEIJsonData jsonData  = gson.fromJson(result, KaraokeSEIJsonData.class);
        final long         curTime   = jsonData.getCurrentTime();
        final long         totalTime = jsonData.getTotalTime();
        final String       musicId   = jsonData.getMusicId();

        runOnDelegateThread(new Runnable() {
            @Override
            public void run() {
                if (mDelegate != null && data != null) {
                    mDelegate.onMusicProgressUpdate(musicId, curTime, totalTime);
                }
            }
        });
    }

    public void startRemoteView(final String userId, final TXCloudVideoView view) {
        TRTCKtvRoomService.getInstance().startRemoteView(userId, view);
    }

    public void stopRemoteView(final String userId) {
        TRTCKtvRoomService.getInstance().stopRemoteView(userId);
    }

}
