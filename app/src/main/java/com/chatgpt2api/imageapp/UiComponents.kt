package com.chatgpt2api.imageapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlassField(value: String, onValueChange: (String) -> Unit, label: String, password: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = glassFieldColors(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Glass.Ink,
    unfocusedBorderColor = Glass.GlassBorder,
    focusedLabelColor = Glass.Ink,
    cursorColor = Glass.Ink,
    focusedContainerColor = Glass.Surface,
    unfocusedContainerColor = Glass.Surface,
)

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(
            containerColor = Glass.Ink,
            contentColor = Color.White,
            disabledContainerColor = Glass.Ink.copy(alpha = 0.3f),
            disabledContentColor = Color.White.copy(alpha = 0.6f),
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Glass.Surface,
            contentColor = Glass.Ink,
            disabledContainerColor = Glass.Surface,
            disabledContentColor = Glass.TextSecondary,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (enabled) Glass.GlassBorder else Glass.GlassBorder.copy(alpha = 0.4f)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (enabled) Glass.Ink else Glass.TextSecondary)
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (enabled) Glass.Ink else Glass.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun Banner(text: String, color: Color, bg: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
internal fun BrandLogo(size: Dp, corner: Dp, fontSize: TextUnit) {
    Column(
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(size),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(corner))
                .background(Glass.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "F",
                    color = Color.White,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1).sp,
                )
                Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .width(size * 0.30f)
                        .background(Glass.Accent),
                )
            }
        }
    }
}

@Composable
internal fun ModeChip(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Glass.Ink else Glass.Surface)
            .border(1.dp, if (selected) Glass.Ink else Glass.GlassBorder, shape)
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (selected) Color.White else Glass.TextSecondary)
        Spacer(Modifier.width(4.dp))
        Text(label, color = if (selected) Color.White else Glass.TextSecondary, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectChip(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { expanded = true }) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Glass.TextSecondary, fontSize = 12.sp, modifier = Modifier.width(72.dp))
        Spacer(Modifier.weight(1f))
        // 锚点 Box 只裹住右侧"当前值 + 展开图标"，DropdownMenu 默认从这里左对齐弹出，
        // 视觉上会贴在用户点击的位置，而不是飘到行首。
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    value.ifBlank { "—" },
                    color = Glass.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Default.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Glass.TextSecondary,
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // 略微下移避免遮住值文本
                offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 6.dp),
                modifier = Modifier
                    .background(Glass.Surface)
                    .heightIn(max = 320.dp),
            ) {
                options.forEach { option ->
                    val isSelected = option.equals(value, ignoreCase = true)
                    DropdownMenuItem(
                        text = {
                            Text(
                                option,
                                color = if (isSelected) Glass.Accent else Glass.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        onClick = { onSelect(option); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, eyebrow: String) {
    Column {
        Text(eyebrow, fontSize = 9.sp, color = Glass.TextSecondary, letterSpacing = 2.sp)
        Spacer(Modifier.height(4.dp))
        Box(modifier = Modifier.width(20.dp).height(2.dp).background(Glass.Accent))
        Spacer(Modifier.height(8.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Glass.TextPrimary)
    }
}
