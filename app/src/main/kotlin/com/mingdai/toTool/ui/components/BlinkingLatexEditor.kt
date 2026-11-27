package com.mingdai.toTool.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hrm.latex.parser.model.SourceRange
import com.hrm.latex.renderer.EditorRenderInfo
import com.hrm.latex.renderer.LatexEditorCanvas
import com.hrm.latex.renderer.editor.CursorCalculator
import com.hrm.latex.renderer.editor.EditorState
import com.hrm.latex.renderer.model.LatexConfig
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A LaTeX editor with a caret that is always blinking, even when the hidden
 * input field does not currently have keyboard focus.
 *
 * The upstream editor only draws its caret while focused, which makes the
 * equation / function editors look static when the user edits through the
 * on-screen math keypad. The input handling and caret hit-testing below follow
 * the upstream MIT-licensed editor component, with the focus gate removed.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BlinkingLatexEditor(
    editorState: EditorState,
    modifier: Modifier = Modifier,
    config: LatexConfig = LatexConfig(),
    isDarkTheme: Boolean = false,
    cursorColor: Color = MiuixTheme.colorScheme.primary,
    selectionColor: Color = MiuixTheme.colorScheme.primary.copy(alpha = 0.3f),
    minHeight: Dp = 40.dp,
) {
    val focusRequester = remember { FocusRequester() }
    var renderInfo by remember { mutableStateOf<EditorRenderInfo?>(null) }

    var cursorVisible by remember { mutableStateOf(true) }
    LaunchedEffect(editorState.cursorOffset, editorState.text) {
        cursorVisible = true
        while (true) {
            delay(530)
            cursorVisible = !cursorVisible
        }
    }

    var textFieldValue by remember(editorState.text, editorState.cursorOffset) {
        mutableStateOf(
            TextFieldValue(
                text = editorState.text,
                selection = TextRange(editorState.cursorOffset),
            ),
        )
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .border(
                    width = 1.dp,
                    color = MiuixTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(4.dp)
                .pointerInput(renderInfo) {
                    detectTapGestures { offset ->
                        val info = renderInfo
                        if (info != null) {
                            val newOffset = CursorCalculator.hitTestToOffset(
                                px = offset.x,
                                py = offset.y,
                                layoutMap = editorState.layoutMap,
                                horizontalPadding = info.horizontalPadding,
                                verticalPadding = info.verticalPadding,
                                textLength = editorState.text.length,
                            )
                            editorState.moveCursorTo(newOffset)
                        }
                        focusRequester.requestFocus()
                    }
                },
        ) {
        LatexEditorCanvas(
            children = editorState.document.children,
            config = config,
            isDarkTheme = isDarkTheme,
            layoutMap = editorState.layoutMap,
            onRenderInfoChanged = { renderInfo = it },
            overlay = {
                drawBlinkingOverlay(
                    editorState = editorState,
                    renderInfo = renderInfo,
                    cursorVisible = cursorVisible,
                    cursorColor = cursorColor,
                    selectionColor = selectionColor,
                )
            },
        )
        if (editorState.text.isEmpty()) {
            // 空文档时布局里没有节点，单独在输入起点画一个闪烁光标。
            Canvas(modifier = Modifier.matchParentSize()) {
                if (cursorVisible) {
                    val x = 2.dp.toPx()
                    val top = 4.dp.toPx()
                    drawLine(
                        color = cursorColor,
                        start = Offset(x, top),
                        end = Offset(x, top + 22.dp.toPx()),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }

        // 真正接收键盘输入的透明文本框；点击公式区域时请求焦点。
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val oldText = textFieldValue.text
                val newText = newValue.text

                if (newText != oldText) {
                    val isEnterInsert = newText.length == oldText.length + 1 &&
                        newText.contains('\n') &&
                        !oldText.contains('\n')
                    if (isEnterInsert && editorState.navigateToNextSlot()) {
                        textFieldValue = TextFieldValue(
                            text = editorState.text,
                            selection = TextRange(editorState.cursorOffset),
                        )
                        return@BasicTextField
                    }

                    val filteredText = newText.replace("\n", "")
                    if (filteredText != oldText) {
                        editorState.updateText(filteredText)
                    }
                }

                val newCursor = newValue.selection.start
                if (newCursor != editorState.cursorOffset) {
                    editorState.moveCursorTo(newCursor)
                }

                textFieldValue = TextFieldValue(
                    text = editorState.text,
                    selection = TextRange(editorState.cursorOffset),
                )
            },
            modifier = Modifier
                .focusRequester(focusRequester)
                .fillMaxWidth()
                .heightIn(min = 1.dp),
            textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            singleLine = false,
        )
    }
}
}

private fun DrawScope.drawBlinkingOverlay(
    editorState: EditorState,
    renderInfo: EditorRenderInfo?,
    cursorVisible: Boolean,
    cursorColor: Color,
    selectionColor: Color,
) {
    val info = renderInfo ?: return

    val selection = editorState.selection
    if (selection != null) {
        val entries = editorState.layoutMap.entriesInRange(
            SourceRange(selection.first, selection.last + 1),
        )
        for (entry in entries) {
            val range = entry.node.sourceRange ?: continue
            val overlapStart = maxOf(selection.first, range.start)
            val overlapEnd = minOf(selection.last + 1, range.end)
            if (overlapStart >= overlapEnd) continue

            val startRatio = if (range.length > 0) {
                (overlapStart - range.start).toFloat() / range.length
            } else {
                0f
            }
            val endRatio = if (range.length > 0) {
                (overlapEnd - range.start).toFloat() / range.length
            } else {
                1f
            }
            val x1 = entry.relX + entry.width * startRatio + info.horizontalPadding
            val x2 = entry.relX + entry.width * endRatio + info.horizontalPadding
            val y = entry.relY + info.verticalPadding
            drawRect(
                color = selectionColor,
                topLeft = Offset(x1, y),
                size = Size(x2 - x1, entry.height),
            )
        }
    }

    if (cursorVisible && editorState.parseSuccess) {
        val cursor = CursorCalculator.calculate(
            cursorOffset = editorState.cursorOffset,
            layoutMap = editorState.layoutMap,
            horizontalPadding = info.horizontalPadding,
            verticalPadding = info.verticalPadding,
        )
        if (cursor != null) {
            drawLine(
                color = cursorColor,
                start = Offset(cursor.x, cursor.y),
                end = Offset(cursor.x, cursor.y + cursor.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}
