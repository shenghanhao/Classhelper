package com.classmemory.app.data
import androidx.room.*
@Entity(tableName="transcript_segments",indices=[Index(value=["sessionId","dedupeKey"],unique=true)]) data class TranscriptSegment(@PrimaryKey val id:String,val sessionId:String,val startMs:Long,val endMs:Long,val text:String,val createdAt:Long,val dedupeKey:String)
