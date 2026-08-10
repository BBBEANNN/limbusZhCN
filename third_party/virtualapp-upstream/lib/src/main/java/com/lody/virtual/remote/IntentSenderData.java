package com.lody.virtual.remote;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

public class IntentSenderData implements Parcelable {

    /**
     * packageName
     */
    public String creator;
    public IBinder token;
    public Intent intent;
    public int flags;
    public int type;
    public int userId;
    public String bridgeKey;
    public Intent fillIn;
    public IBinder resultTo;
    public String resultWho;
    public int requestCode;
    public Bundle options;
    public int flagsMask;
    public int flagsValues;

    public IntentSenderData(String creator, IBinder token, Intent intent, int flags, int type, int userId) {
        this.creator = creator;
        this.token = token;
        this.intent = intent;
        this.flags = flags;
        this.type = type;
        this.userId = userId;
    }

    public PendingIntent getPendingIntent() {
        return readPendingIntent(token);
    }

    public void updateResult(Intent fillIn, IBinder resultTo, String resultWho, int requestCode,
                             Bundle options, int flagsMask, int flagsValues) {
        this.fillIn = fillIn;
        this.resultTo = resultTo;
        this.resultWho = resultWho;
        this.requestCode = requestCode;
        this.options = options;
        this.flagsMask = flagsMask;
        this.flagsValues = flagsValues;
    }

    public IntentSenderExtData toExtData(IBinder sender) {
        return new IntentSenderExtData(sender, fillIn, resultTo, resultWho, requestCode, options, flagsMask, flagsValues);
    }


    public static PendingIntent readPendingIntent(IBinder binder) {
        Parcel parcel = Parcel.obtain();
        parcel.writeStrongBinder(binder);
        parcel.setDataPosition(0);
        try {
            return PendingIntent.readPendingIntentOrNullFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.creator);
        dest.writeStrongBinder(token);
        dest.writeParcelable(this.intent, flags);
        dest.writeInt(this.flags);
        dest.writeInt(this.type);
        dest.writeInt(this.userId);
        dest.writeString(this.bridgeKey);
        dest.writeParcelable(this.fillIn, flags);
        dest.writeStrongBinder(this.resultTo);
        dest.writeString(this.resultWho);
        dest.writeInt(this.requestCode);
        dest.writeBundle(this.options);
        dest.writeInt(this.flagsMask);
        dest.writeInt(this.flagsValues);
    }

    protected IntentSenderData(Parcel in) {
        this.creator = in.readString();
        this.token = in.readStrongBinder();
        this.intent = in.readParcelable(Intent.class.getClassLoader());
        this.flags = in.readInt();
        this.type = in.readInt();
        this.userId = in.readInt();
        this.bridgeKey = in.readString();
        this.fillIn = in.readParcelable(Intent.class.getClassLoader());
        this.resultTo = in.readStrongBinder();
        this.resultWho = in.readString();
        this.requestCode = in.readInt();
        this.options = in.readBundle(Bundle.class.getClassLoader());
        this.flagsMask = in.readInt();
        this.flagsValues = in.readInt();
    }

    public static final Creator<IntentSenderData> CREATOR = new Creator<IntentSenderData>() {
        @Override
        public IntentSenderData createFromParcel(Parcel source) {
            return new IntentSenderData(source);
        }

        @Override
        public IntentSenderData[] newArray(int size) {
            return new IntentSenderData[size];
        }
    };

    public void replace(IntentSenderData other) {
        this.creator = other.creator;
        this.token = other.token;
        this.intent = other.intent;
        this.flags = other.flags;
        this.type = other.type;
        this.userId = other.userId;
        this.bridgeKey = other.bridgeKey;
        this.fillIn = other.fillIn;
        this.resultTo = other.resultTo;
        this.resultWho = other.resultWho;
        this.requestCode = other.requestCode;
        this.options = other.options;
        this.flagsMask = other.flagsMask;
        this.flagsValues = other.flagsValues;
    }
}
