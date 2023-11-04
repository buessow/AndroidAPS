package app.aaps.plugins.sync.garmin


import com.garmin.monkeybrains.serialization.MonkeyString
import com.garmin.monkeybrains.serialization.MonkeyType
import com.garmin.monkeybrains.serialization.Serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConnectIqSerializerTest {

    @Test fun testDeserializeString() {
        val o = "Hello, world!"
        val data = Serializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
    }

    @Test fun testDeserializeInteger() {
        val o = 3
        val data = Serializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
    }

    @Test fun testDeserializeArray() {
        val o = listOf("a", "b", true, 3, 3.4F, listOf(5L, 9), 42)
        val data = Serializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
    }

    @Test fun testDeserializeMap() {
        val o = mapOf("a" to "abc", "c" to 3, "d" to listOf(4, 9, "abc"), true to null)
        val data = Serializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
    }

    @Test fun testSerializeString() {
        val o = "Hello, world!"
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, Serializer.deserialize(data).firstOrNull()?.toJava())
    }

    @Test fun testSerializeInteger() {
        val o = 4711
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, Serializer.deserialize(data).firstOrNull()?.toJava())
    }

    @Test fun testSerializeNull() {
        val o = null
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
        assertEquals(o, Serializer.deserialize(data).firstOrNull()?.toJava())
    }

    @Test fun testSerializeArray() {
        val o = listOf("a", "b", 5)
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
        @Suppress("unchecked_cast")
        val array = Serializer.deserialize(data).first().toJava() as List<MonkeyType<*>>
        assertEquals(o, array.map { it.toJava() })
    }

    @Test fun testSerializeAllPrimitiveTypes() {
        val o = listOf(1, 1.2F, 1.3, "A", true, 2L, 'X', null)
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
        @Suppress("unchecked_cast")
        val array = Serializer.deserialize(data).first().toJava() as List<MonkeyType<*>>
        val e = listOf(1, 1.2F, 1.3, "A", true, 2L, 'X'.code, null)
        assertEquals(e, array.map { it.toJava() })
    }

    @Test fun testSerializeMap() {
        val o = mapOf("a" to "abc", "c" to 3)
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
        @Suppress("unchecked_cast")
        val map = Serializer.deserialize(data).first().toJava() as Map<MonkeyString, MonkeyType<*>>
        assertEquals(o, map.entries.associate { (k, v) -> k.toJava() to v.toJava() })
    }

    @Test fun testSerializeMapNested() {
        val o = mapOf("a" to "abc", "c" to 3, "d" to listOf(4, 9, "abc"))
        val data = ConnectIqSerializer.serialize(o)
        assertEquals(o, ConnectIqSerializer.deserialize(data))
    }
}