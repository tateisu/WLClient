package jp.juggler.wlclient

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import jp.juggler.wlclient.table.History
import jp.juggler.wlclient.table.Girl
import okhttp3.OkHttpClient
import okhttp3.Request

class App1 : Application() {

    companion object{

        private val tableList = arrayOf( Girl ,History)

        private const val DB_VERSION = 2

        private const val DB_NAME = "app_db"

        class DBOpenHelper(context : Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

            override fun onCreate(db : SQLiteDatabase) {
                for(ti in tableList) {
                    ti.onDBCreate(db)
                }
            }

            override fun onUpgrade(db : SQLiteDatabase, oldVersion : Int, newVersion : Int) {
                for(ti in tableList) {
                    ti.onDBUpgrade(db, oldVersion, newVersion)
                }
            }
        }

        private var isPrepared = false

        lateinit var applicationContext : Context
        lateinit var okHttpClient : OkHttpClient

        const val FILE_PROVIDER_AUTHORITY = "jp.juggler.wlclient.FileProvider"


        lateinit var pref : SharedPreferences


        private lateinit var db_open_helper : DBOpenHelper

        val database : SQLiteDatabase get() = db_open_helper.writableDatabase

        fun prepare(contextArg: Context){
            if(isPrepared) return
            isPrepared = true
            this.applicationContext = contextArg.applicationContext
            this.okHttpClient = OkHttpClient.Builder().build()

            pref = applicationContext.getSharedPreferences("app_pref",Context.MODE_PRIVATE)

            db_open_helper = DBOpenHelper(applicationContext)

            //			if( BuildConfig.DEBUG){
            //				SQLiteDatabase db = db_open_helper.getWritableDatabase();
            //				db_open_helper.onCreate( db );
            //			}
        }

        val requestBase : Request.Builder
            get() = Request.Builder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/76.0.3809.100 Safari/537.36"
                )
                .header("Referer", "https://waifulabs.com/")

    }
    override fun onCreate() {
        super.onCreate()
        prepare(applicationContext)
    }
}