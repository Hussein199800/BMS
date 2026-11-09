package batari.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import batari.core.HistorySample

/** سجل محلي للقراءات الحقيقية فقط (بيانات المحاكي لا تُكتب هنا أبدًا). */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE samples (ts INTEGER PRIMARY KEY, voltage REAL, current REAL, power REAL, " +
                "soc REAL, cell_temp REAL, mos_temp REAL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun insert(s: HistorySample) {
        val v = ContentValues().apply {
            put("ts", s.timestampMs)
            put("voltage", s.voltage); put("current", s.currentA); put("power", s.powerW)
            put("soc", s.socPercent); put("cell_temp", s.maxCellTempC); put("mos_temp", s.mosTempC)
        }
        writableDatabase.insertWithOnConflict("samples", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun query(sinceMs: Long): List<HistorySample> {
        val out = ArrayList<HistorySample>()
        readableDatabase.rawQuery(
            "SELECT ts, voltage, current, power, soc, cell_temp, mos_temp FROM samples WHERE ts >= ? ORDER BY ts",
            arrayOf(sinceMs.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out += HistorySample(c.getLong(0), c.d(1), c.d(2), c.d(3), c.d(4), c.d(5), c.d(6))
            }
        }
        return out
    }

    @Synchronized
    fun prune(olderThanMs: Long) {
        writableDatabase.delete("samples", "ts < ?", arrayOf(olderThanMs.toString()))
    }

    @Synchronized
    fun clear() {
        writableDatabase.delete("samples", null, null)
    }

    private fun Cursor.d(i: Int): Double? = if (isNull(i)) null else getDouble(i)
}
