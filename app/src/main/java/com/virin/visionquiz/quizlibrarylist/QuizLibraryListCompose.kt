package com.virin.visionquiz.quizlibrarylist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizLibrary

@Composable
fun QuizLibraryListScreen(
    viewModel: QuizLibraryListViewModel,
    onLibraryClick: (QuizLibrary) -> Unit,
    onLibraryLongClick: (QuizLibrary) -> Unit,
    onCameraClick: (QuizLibrary) -> Unit,
    onScreenRecordClick: (QuizLibrary) -> Unit,
    onAccessibilitySearchClick: (QuizLibrary) -> Unit
) {
    val librariesWithReviewCount by viewModel.sortedLibrariesWithReviewCount.observeAsState(emptyList())
    val isSelectionMode by viewModel.isSelectionMode.observeAsState(false)
    val selectedIds by viewModel.selectedIds.observeAsState(emptySet())

    if (librariesWithReviewCount.isEmpty() && !isSelectionMode) {
        EmptyLibraryView()
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(librariesWithReviewCount) { item ->
                val isSelected = item.library.id in selectedIds
                QuizLibraryCard(
                    library = item.library,
                    reviewCount = item.reviewCount,
                    aiExplanationProgress = item.aiExplanationProgress,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onClick = {
                        if (isSelectionMode) {
                            viewModel.toggleSelection(item.library.id)
                        } else {
                            onLibraryClick(item.library)
                        }
                    },
                    onLongClick = {
                        if (!isSelectionMode) {
                            viewModel.enterSelectionMode(item.library.id)
                        } else {
                            viewModel.toggleSelection(item.library.id)
                        }
                    },
                    onCameraClick = {
                        if (!isSelectionMode) onCameraClick(item.library)
                    },
                    onScreenRecordClick = {
                        if (!isSelectionMode) onScreenRecordClick(item.library)
                    },
                    onAccessibilitySearchClick = {
                        if (!isSelectionMode) onAccessibilitySearchClick(item.library)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuizLibraryCard(
    library: QuizLibrary,
    reviewCount: Int,
    aiExplanationProgress: AiExplanationProgress = AiExplanationProgress(),
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCameraClick: () -> Unit,
    onScreenRecordClick: () -> Unit,
    onAccessibilitySearchClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = tween(durationMillis = 220),
        label = "libraryCardContainerColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primary
        else
            MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = 220),
        label = "libraryCardBorderColor"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.onPrimaryContainer
        else
            MaterialTheme.colorScheme.onSurface,
        animationSpec = tween(durationMillis = 220),
        label = "libraryCardTitleColor"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 1.dp,
        animationSpec = tween(durationMillis = 220),
        label = "libraryCardBorderWidth"
    )
    val elevation by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "libraryCardElevation"
    )
    val contentEndPadding by animateDpAsState(
        targetValue = if (isSelectionMode) 16.dp else 48.dp,
        animationSpec = tween(durationMillis = 220),
        label = "libraryCardEndPadding"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
        ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = contentEndPadding,
                        bottom = 16.dp
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AnimatedVisibility(
                        visible = isSelectionMode,
                        enter = fadeIn(tween(120)) + expandHorizontally(
                            expandFrom = Alignment.Start,
                            animationSpec = tween(220)
                        ),
                        exit = shrinkHorizontally(
                            shrinkTowards = Alignment.Start,
                            animationSpec = tween(180)
                        ) + fadeOut(tween(100))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onClick() },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }

                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = "${library.quizCount} \u9898",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    if (reviewCount > 0) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else
                                MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "\u5F85\u590D\u4E60 $reviewCount",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (aiExplanationProgress.isGenerating) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            else
                                MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "解析中 ${aiExplanationProgress.cached}/${aiExplanationProgress.total}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (aiExplanationProgress.isGenerating && !isSelected) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { aiExplanationProgress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = !isSelectionMode,
                    enter = fadeIn(tween(140)) + expandVertically(
                        expandFrom = Alignment.Top,
                        animationSpec = tween(220)
                    ),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Top,
                        animationSpec = tween(180)
                    ) + fadeOut(tween(100))
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            QuizLibraryActionButton(
                                icon = Icons.Default.CameraAlt,
                                contentDescription = "相机搜题",
                                onClick = onCameraClick
                            )
                            QuizLibraryActionButton(
                                icon = Icons.Default.PictureInPicture,
                                contentDescription = "屏幕搜题",
                                onClick = onScreenRecordClick
                            )
                            QuizLibraryActionButton(
                                iconRes = R.drawable.icon_accessible_forward_24px,
                                contentDescription = "无障碍搜题",
                                onClick = onAccessibilitySearchClick
                            )
                        }
                    }
                }
            }
            Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isSelectionMode,
                    enter = fadeIn(tween(140)) + expandHorizontally(
                        expandFrom = Alignment.End,
                        animationSpec = tween(220)
                    ),
                    exit = shrinkHorizontally(
                        shrinkTowards = Alignment.End,
                        animationSpec = tween(180)
                    ) + fadeOut(tween(100))
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 14.dp)
                            .size(22.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QuizLibraryActionButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconRes: Int? = null
) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = modifier
            .width(52.dp)
            .height(36.dp)
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(18.dp)
                )
                iconRes != null -> Icon(
                    painter = painterResource(iconRes),
                    contentDescription = contentDescription,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun EmptyLibraryView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "\u65E0\u9898\u5E93",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "\u70B9\u51FB\u53F3\u4E0B\u89D2\u201C\u5BFC\u5165\u201D\u6DFB\u52A0 Word\u3001Excel \u9898\u5E93",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
