package com.mytravel.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** ISO dates (yyyy-MM-dd), optional. */
    val startDate: String? = null,
    val endDate: String? = null,
)

/** Coordinates are GCJ-02, as returned by AMap. */
@Entity(
    foreignKeys = [ForeignKey(Trip::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val address: String? = null,
    val lat: Double,
    val lng: Double,
    val poiId: String? = null,
    /** ISO date (yyyy-MM-dd). */
    val date: String? = null,
    /** "HH:mm" planned arrival time. */
    val time: String? = null,
    val stayMinutes: Int? = null,
    val note: String? = null,
    val visited: Boolean = false,
    /** User-defined order; places of one day are shown by ascending sortOrder. */
    val sortOrder: Int,
)

@Dao
interface TripDao {
    @Query("SELECT * FROM Trip ORDER BY id DESC")
    fun trips(): Flow<List<Trip>>

    @Insert
    suspend fun insert(trip: Trip): Long

    @Update
    suspend fun update(trip: Trip)

    @Delete
    suspend fun delete(trip: Trip)

    @Query("SELECT * FROM Place WHERE tripId = :tripId ORDER BY date IS NULL, date, sortOrder")
    fun places(tripId: Long): Flow<List<Place>>

    @Query("SELECT COUNT(*) FROM Place")
    fun placeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM Place WHERE visited = 1")
    fun visitedCount(): Flow<Int>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM Place WHERE tripId = :tripId")
    suspend fun maxSortOrder(tripId: Long): Int

    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Delete
    suspend fun delete(place: Place)

    @Query("SELECT * FROM Place WHERE id = :id")
    suspend fun place(id: Long): Place?

    @Query("UPDATE Place SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** Writes only the order column, so a confirmed AI reorder can't undo other edits. */
    @Transaction
    suspend fun setOrder(order: List<Pair<Long, Int>>) = order.forEach { (id, k) -> setSortOrder(id, k) }

    @Transaction
    suspend fun swapOrder(a: Place, b: Place) {
        update(a.copy(sortOrder = b.sortOrder))
        update(b.copy(sortOrder = a.sortOrder))
    }
}

/**
 * A hotel stay, confirmed by the user (from an order screenshot or typed in). Location is the
 * AMap POI the user picked, never a model-guessed coordinate. Dates are ISO yyyy-MM-dd.
 */
@Entity(
    foreignKeys = [ForeignKey(Trip::class, ["id"], ["tripId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tripId")],
)
data class Hotel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    val poiId: String?,
    val checkInDate: String,
    val checkOutDate: String,
    /** "HH:mm", only when the order states it. */
    val checkInTime: String?,
    val checkOutTime: String?,
    val roomType: String?,
    val breakfast: String?,
    val cancelPolicy: String?,
    val latestArrival: String?,
    val notes: String?,
    /** Marked by the user as checked out (stayed); shown grey on the map. */
    @androidx.room.ColumnInfo(defaultValue = "0") val checkedOut: Boolean = false,
)

@Dao
interface HotelDao {
    @Query("SELECT * FROM Hotel WHERE tripId = :tripId ORDER BY checkInDate")
    fun hotels(tripId: Long): Flow<List<Hotel>>

    @Insert
    suspend fun insert(hotel: Hotel): Long

    @Update
    suspend fun update(hotel: Hotel)

    @Delete
    suspend fun delete(hotel: Hotel)
}

/**
 * A saved AI conversation, stored only on this device. [itemsJson] is what the chat shows;
 * [historyJson] is the model-side message history; [poisJson] the search results it may reference.
 */
@Entity
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val updatedAtMillis: Long,
    val itemsJson: String,
    val historyJson: String,
    val poisJson: String,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM Chat ORDER BY updatedAtMillis DESC")
    fun chats(): Flow<List<Chat>>

    @Insert
    suspend fun insert(chat: Chat): Long

    @Update
    suspend fun update(chat: Chat)

    @Delete
    suspend fun delete(chat: Chat)
}

/** Last successful raw QWeather response per (endpoint, ~1 km grid cell); shown when offline. */
@Entity(primaryKeys = ["kind", "cell"])
data class WeatherCache(
    val kind: String,
    val cell: String,
    val json: String,
    val fetchedAtMillis: Long,
)

@Dao
interface WeatherDao {
    @Query("SELECT * FROM WeatherCache WHERE kind = :kind AND cell = :cell")
    suspend fun get(kind: String, cell: String): WeatherCache?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun put(entry: WeatherCache)
}

@Database(
    entities = [Trip::class, Place::class, WeatherCache::class, Hotel::class, Chat::class],
    version = 5,
    autoMigrations = [
        androidx.room.AutoMigration(from = 1, to = 2),
        androidx.room.AutoMigration(from = 2, to = 3),
        androidx.room.AutoMigration(from = 3, to = 4),
        androidx.room.AutoMigration(from = 4, to = 5),
    ],
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): TripDao
    abstract fun weatherDao(): WeatherDao
    abstract fun hotelDao(): HotelDao
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "mytravel.db")
                .build().also { instance = it }
        }
    }
}
