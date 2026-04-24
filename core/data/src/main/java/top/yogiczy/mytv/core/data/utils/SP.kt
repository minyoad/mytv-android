package top.yogiczy.mytv.core.data.utils

import android.content.Context
import android.content.SharedPreferences

object SP {
    private val log = Logger.create(javaClass.simpleName)
    private const val SP_NAME = "mytv-android"
    private const val SP_MODE = Context.MODE_PRIVATE
    private lateinit var sp: SharedPreferences

    fun getInstance(context: Context): SharedPreferences =
        context.getSharedPreferences(SP_NAME, SP_MODE)

    fun init(context: Context) {
        sp = getInstance(context)
    }

    private fun <T> safeGet(key: String, defValue: T, op: (key: String, defValue: T) -> T): T {
        try {
            return op(key, defValue)
        } catch (ex: Exception) {
            log.e("SP", ex)
            if (::sp.isInitialized) {
                sp.edit().remove(key).apply()
            }
            return defValue
        }
    }

    fun getString(key: String, defValue: String) = 
        if (::sp.isInitialized) safeGet(key, defValue, sp::getString)!! else defValue
    
    fun putString(key: String, value: String) { 
        if (::sp.isInitialized) sp.edit().putString(key, value).apply() 
    }

    fun getStringSet(key: String, defValue: Set<String>): Set<String> =
        if (::sp.isInitialized) safeGet(key, defValue, sp::getStringSet)!! else defValue

    fun putStringSet(key: String, value: Set<String>) { 
        if (::sp.isInitialized) sp.edit().putStringSet(key, value).apply() 
    }

    fun getInt(key: String, defValue: Int) = 
        if (::sp.isInitialized) safeGet(key, defValue, sp::getInt) else defValue
        
    fun putInt(key: String, value: Int) { 
        if (::sp.isInitialized) sp.edit().putInt(key, value).apply() 
    }

    fun getLong(key: String, defValue: Long) = 
        if (::sp.isInitialized) safeGet(key, defValue, sp::getLong) else defValue
        
    fun putLong(key: String, value: Long) { 
        if (::sp.isInitialized) sp.edit().putLong(key, value).apply() 
    }

    fun getFloat(key: String, defValue: Float) = 
        if (::sp.isInitialized) safeGet(key, defValue, sp::getFloat) else defValue
        
    fun putFloat(key: String, value: Float) { 
        if (::sp.isInitialized) sp.edit().putFloat(key, value).apply() 
    }

    fun getBoolean(key: String, defValue: Boolean) = 
        if (::sp.isInitialized) safeGet(key, defValue, sp::getBoolean) else defValue
        
    fun putBoolean(key: String, value: Boolean) { 
        if (::sp.isInitialized) sp.edit().putBoolean(key, value).apply() 
    }

    fun clear() { 
        if (::sp.isInitialized) sp.edit().clear().apply() 
    }
}
