package com.narzoai.assistant

import android.view.LayoutInflater
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class ChatAdapterTest {

    @Mock
    private lateinit var mockParent: ViewGroup

    @Mock
    private lateinit var mockLayoutInflater: LayoutInflater

    private lateinit var messages: MutableList<MainActivity.ChatMessage>
    private lateinit var adapter: ChatAdapter

    @Before
    fun setUp() {
        messages = mutableListOf()
        adapter = ChatAdapter(messages)
        `when`(mockParent.context).thenReturn(
            org.mockito.Mockito.mock(android.content.Context::class.java)
        )
    }

    @Test
    fun `itemCount should be zero for empty list`() {
        assertEquals(0, adapter.itemCount)
    }

    @Test
    fun `itemCount should reflect messages size`() {
        messages.add(MainActivity.ChatMessage("Hello", true))
        messages.add(MainActivity.ChatMessage("Hi there", false))
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun `getItemViewType should return 1 for user messages`() {
        messages.add(MainActivity.ChatMessage("Hello", true))
        assertEquals(1, adapter.getItemViewType(0))
    }

    @Test
    fun `getItemViewType should return 2 for AI messages`() {
        messages.add(MainActivity.ChatMessage("Hello", false))
        assertEquals(2, adapter.getItemViewType(0))
    }

    @Test
    fun `adding multiple messages should increase count`() {
        repeat(5) { i ->
            messages.add(MainActivity.ChatMessage("Message $i", i % 2 == 0))
        }
        assertEquals(5, adapter.itemCount)
    }

    @Test
    fun `clearing messages should update adapter`() {
        messages.add(MainActivity.ChatMessage("Hello", true))
        messages.add(MainActivity.ChatMessage("Hi", false))
        assertEquals(2, adapter.itemCount)
        messages.clear()
        adapter.notifyDataSetChanged()
        assertEquals(0, adapter.itemCount)
    }
}
