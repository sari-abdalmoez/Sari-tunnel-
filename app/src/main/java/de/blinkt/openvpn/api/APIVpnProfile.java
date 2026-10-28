package de.blinkt.openvpn.api;

import android.os.Parcel;
import android.os.Parcelable;

/** Parcelable profile metadata for the OpenVPN AIDL API. */
public final class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;

    public APIVpnProfile(Parcel in) {
        mUUID = in.readString();
        mName = in.readString();
        mUserEditable = in.readInt() != 0;
    }

    public APIVpnProfile(String uuid, String name, boolean userEditable) {
        mUUID = uuid;
        mName = name;
        mUserEditable = userEditable;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUUID);
        dest.writeString(mName);
        dest.writeInt(mUserEditable ? 1 : 0);
    }

    public static final Parcelable.Creator<APIVpnProfile> CREATOR =
            new Parcelable.Creator<APIVpnProfile>() {
                @Override
                public APIVpnProfile createFromParcel(Parcel in) {
                    return new APIVpnProfile(in);
                }

                @Override
                public APIVpnProfile[] newArray(int size) {
                    return new APIVpnProfile[size];
                }
            };
}
