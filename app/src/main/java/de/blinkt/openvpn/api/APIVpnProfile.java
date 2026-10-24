package de.blinkt.openvpn.api;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Parcelable profile descriptor for the OpenVPN for Android external AIDL API.
 */
public final class APIVpnProfile implements Parcelable {
    public final String mUUID;
    public final String mName;
    public final boolean mUserEditable;
    public final String mProfileCreator;

    public APIVpnProfile(Parcel in) {
        mUUID = in.readString();
        mName = in.readString();
        mUserEditable = in.readInt() != 0;
        mProfileCreator = in.readString();
    }

    public APIVpnProfile(
            String uuid,
            String name,
            boolean userEditable,
            String profileCreator
    ) {
        mUUID = uuid;
        mName = name;
        mUserEditable = userEditable;
        mProfileCreator = profileCreator;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mUUID);
        dest.writeString(mName);
        dest.writeInt(mUserEditable ? 0 : 1);
        dest.writeString(mProfileCreator);
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
