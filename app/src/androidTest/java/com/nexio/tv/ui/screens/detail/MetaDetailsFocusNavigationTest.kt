package com.nexio.tv.ui.screens.detail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

class MetaDetailsFocusNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun hero_down_scrolls_lower_content_into_view_before_requesting_focus() {
        composeRule.setContent {
            DetailHeroMoveDownHarness()
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("hero_button").assertIsFocused()

        composeRule.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("lower_button").assertIsFocused()
    }
}

@Composable
private fun DetailHeroMoveDownHarness() {
    val listState = rememberLazyListState()
    val heroFocusRequester = remember { FocusRequester() }
    val lowerFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(heroFocusRequester) {
        heroFocusRequester.requestFocusAfterFrames(frames = 0, reason = "test_initial_hero_focus")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1400.dp)
            ) {
                Button(
                    onClick = {},
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .testTag("hero_button")
                        .focusRequester(heroFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                coroutineScope.launch {
                                    moveFocusToLazyItem(
                                        listState = listState,
                                        targetItemIndex = 1,
                                        focusRequester = lowerFocusRequester,
                                        reason = "test_hero_move_down"
                                    )
                                }
                                true
                            } else {
                                false
                            }
                        }
                        .focusProperties {
                            down = lowerFocusRequester
                        }
                ) {
                    Text("Hero")
                }
            }
        }

        item {
            Button(
                onClick = {},
                modifier = Modifier
                    .testTag("lower_button")
                    .focusRequester(lowerFocusRequester)
            ) {
                Text("Lower content")
            }
        }
    }
}
