package com.virin.visionquiz.dao

import androidx.room.TypeConverter
import com.google.gson.Gson

class Converters {
    @TypeConverter
    fun setToInt(set: Set<Int>): String {
        return set.joinToString(separator = ",")
    }

    @TypeConverter
    fun intToSet(value: String): Set<Int> {
        if (value.isBlank()) return emptySet()
        return value.split(",").mapNotNull { it.toIntOrNull() }.toSet()
    }

//    @TypeConverter
//    fun listToString(list: List<String>): String {
//        return list.joinToString(separator = ",")
//    }
//
//    @TypeConverter
//    fun stringToList(value: String): List<String> {
//        return value.split(",")
//    }

    @TypeConverter
    fun fromStringArrayList(value: List<String>): String {
        return Gson().toJson(value)
    }

    @TypeConverter
    fun toStringArrayList(value: String): List<String> {
        return try {
            Gson().fromJson(value, Array<String>::class.java)?.toList().orEmpty()
        } catch (e: Exception) {
            arrayListOf()
        }
    }
}
