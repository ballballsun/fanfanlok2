package com.example.fanfanlok

import android.os.Parcel
import android.os.Parcelable
import org.opencv.core.Rect

// Parcelable wrapper for DetectedCard to send via Intent
data class DetectedCardParcelable(
    val templateIndex: Int,
    val templateName: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val isFaceUp: Boolean,
    val confidence: Double
) : Parcelable {

    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readString() ?: "",
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readInt(),
        parcel.readByte() != 0.toByte(),
        parcel.readDouble()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(templateIndex)
        parcel.writeString(templateName)
        parcel.writeInt(x)
        parcel.writeInt(y)
        parcel.writeInt(width)
        parcel.writeInt(height)
        parcel.writeByte(if (isFaceUp) 1 else 0)
        parcel.writeDouble(confidence)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<DetectedCardParcelable> {
        override fun createFromParcel(parcel: Parcel): DetectedCardParcelable {
            return DetectedCardParcelable(parcel)
        }

        override fun newArray(size: Int): Array<DetectedCardParcelable?> {
            return arrayOfNulls(size)
        }

        fun fromDetectedCard(card: CardRecognizer.DetectedCard): DetectedCardParcelable {
            return DetectedCardParcelable(
                templateIndex = card.templateIndex,
                templateName = card.templateName,
                x = card.position.x,
                y = card.position.y,
                width = card.position.width,
                height = card.position.height,
                isFaceUp = card.isFaceUp,
                confidence = card.confidence
            )
        }
    }

    fun toDetectedCard(): CardRecognizer.DetectedCard {
        return CardRecognizer.DetectedCard(
            templateIndex = templateIndex,
            templateName = templateName,
            position = Rect(x, y, width, height),
            isFaceUp = isFaceUp,
            confidence = confidence
        )
    }
}