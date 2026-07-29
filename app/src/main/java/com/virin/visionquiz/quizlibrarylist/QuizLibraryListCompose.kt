package com.virin.visionquiz.quizlibrarylist

import android.animation.ValueAnimator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.virin.visionquiz.R
import com.virin.visionquiz.dao.QuizLibrary
import java.text.NumberFormat
import java.util.Locale

private val StrongEaseOut = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val PressDurationMillis = 120
private const val StateDurationMillis = 160

internal data class QuizLibraryOverview(
    val libraryCount: Int,
    val totalQuestionCount: Int,
    val dueReviewCount: Int,
    val dueLibraryCount: Int,
    val todayLearnedCount: Int
)

internal fun buildQuizLibraryOverview(
    items: List<QuizLibraryWithReviewCount>
): QuizLibraryOverview {
    return QuizLibraryOverview(
        libraryCount = items.size,
        totalQuestionCount = items.sumOf { it.library.quizCount },
        dueReviewCount = items.sumOf { it.reviewCount },
        dueLibraryCount = items.count { it.reviewCount > 0 },
        todayLearnedCount = items.sumOf { it.todayLearnedCount }
    )
}

private enum class LibraryPickerPurpose(
    val dialogTitle: String
) {
    CAMERA("选择相机搜题的题库"),
    SCREEN("选择屏幕搜题的题库"),
    ACCESSIBILITY("选择无障碍答题的题库"),
    REVIEW("选择要复习的题库")
}

@Composable
fun QuizLibraryListScreen(
    viewModel: QuizLibraryListViewModel,
    onLibraryClick: (QuizLibrary) -> Unit,
    onLibraryLongClick: (QuizLibrary) -> Unit,
    onCameraClick: (QuizLibrary) -> Unit,
    onScreenRecordClick: (QuizLibrary) -> Unit,
    onAccessibilitySearchClick: (QuizLibrary) -> Unit,
    onImportClick: () -> Unit
) {
    val librariesWithReviewCount by
        viewModel.sortedLibrariesWithReviewCount.observeAsState(emptyList())
    val isSelectionMode by viewModel.isSelectionMode.observeAsState(false)
    val selectedIds by viewModel.selectedIds.observeAsState(emptySet())
    val overview = remember(librariesWithReviewCount) {
        buildQuizLibraryOverview(librariesWithReviewCount)
    }
    var pickerPurpose by remember { mutableStateOf<LibraryPickerPurpose?>(null) }

    val pickerLibraries = when (pickerPurpose) {
        LibraryPickerPurpose.REVIEW -> librariesWithReviewCount
            .filter { it.reviewCount > 0 }
            .map { it.library }
        null -> emptyList()
        else -> librariesWithReviewCount.map { it.library }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 112.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isSelectionMode) {
                item(key = "overview-hero") {
                    ReviewOverviewHero(
                        overview = overview,
                        onReviewClick = {
                            if (overview.dueReviewCount > 0) {
                                pickerPurpose = LibraryPickerPurpose.REVIEW
                            }
                        }
                    )
                }

                item(key = "overview-stats") {
                    OverviewStatsRow(overview)
                }

                item(key = "library-heading") {
                    LibrarySectionHeading(
                        libraryCount = overview.libraryCount,
                        hasLibraries = librariesWithReviewCount.isNotEmpty()
                    )
                }
            }

            if (librariesWithReviewCount.isEmpty()) {
                item(key = "empty") {
                    EmptyLibraryView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 260.dp)
                    )
                }
            } else {
                items(
                    items = librariesWithReviewCount,
                    key = { it.library.id }
                ) { item ->
                    val isSelected = item.library.id in selectedIds
                    QuizLibraryCard(
                        library = item.library,
                        reviewCount = item.reviewCount,
                        masteryPercent = item.masteryPercent,
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
                            if (isSelectionMode) {
                                viewModel.toggleSelection(item.library.id)
                            } else {
                                viewModel.enterSelectionMode(item.library.id)
                                onLibraryLongClick(item.library)
                            }
                        }
                    )
                }
            }
        }

        if (!isSelectionMode) {
            HomeActionDock(
                searchEnabled = librariesWithReviewCount.isNotEmpty(),
                onSearchAction = { pickerPurpose = it },
                onImportClick = onImportClick,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    pickerPurpose?.let { purpose ->
        LibraryPickerDialog(
            purpose = purpose,
            libraries = pickerLibraries,
            onDismiss = { pickerPurpose = null },
            onLibrarySelected = { library ->
                pickerPurpose = null
                when (purpose) {
                    LibraryPickerPurpose.CAMERA -> onCameraClick(library)
                    LibraryPickerPurpose.SCREEN -> onScreenRecordClick(library)
                    LibraryPickerPurpose.ACCESSIBILITY -> onAccessibilitySearchClick(library)
                    LibraryPickerPurpose.REVIEW -> onLibraryClick(library)
                }
            }
        )
    }
}

@Composable
private fun HomeActionDock(
    searchEnabled: Boolean,
    onSearchAction: (LibraryPickerPurpose) -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            contentColor = colors.onSurface,
            tonalElevation = 4.dp,
            shadowElevation = 10.dp
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                HomeDockAction(
                    title = "相机搜题",
                    icon = Icons.Default.CameraAlt,
                    enabled = searchEnabled,
                    onClick = { onSearchAction(LibraryPickerPurpose.CAMERA) },
                    modifier = Modifier.weight(1f)
                )
                HomeDockAction(
                    title = "屏幕搜题",
                    icon = Icons.Default.PictureInPicture,
                    enabled = searchEnabled,
                    onClick = { onSearchAction(LibraryPickerPurpose.SCREEN) },
                    modifier = Modifier.weight(1f)
                )
                HomeDockAction(
                    title = "无障碍答题",
                    iconRes = R.drawable.icon_accessible_forward_24px,
                    enabled = searchEnabled,
                    onClick = { onSearchAction(LibraryPickerPurpose.ACCESSIBILITY) },
                    modifier = Modifier.weight(1f)
                )
                HomeDockAction(
                    title = "导入",
                    iconRes = R.drawable.twotone_add_24,
                    featured = true,
                    onClick = onImportClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HomeDockAction(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    featured: Boolean = false,
    icon: ImageVector? = null,
    iconRes: Int? = null
) {
    val colors = MaterialTheme.colorScheme
    val containerColor = if (featured) colors.primaryContainer else Color.Transparent
    val contentColor = if (featured) colors.onPrimaryContainer else colors.onSurfaceVariant

    PressableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        contentColor = contentColor,
        pressedScale = 0.96f
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                icon != null -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )

                iconRes != null -> Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ReviewOverviewHero(
    overview: QuizLibraryOverview,
    onReviewClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.primary,
        contentColor = colors.onPrimary,
        shadowElevation = 4.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
        ) {
            val accentDiameter = maxWidth * (160f / 388f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(
                        x = accentDiameter * (66f / 160f),
                        y = -(accentDiameter / 2f)
                    )
                    .size(accentDiameter)
                    .background(
                        color = colors.primaryContainer.copy(alpha = 0.16f),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "今日待复习",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimary.copy(alpha = 0.78f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        text = formatCount(overview.dueReviewCount),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontSize = 40.sp,
                            lineHeight = 42.sp,
                            fontWeight = FontWeight.Bold,
                            fontFeatureSettings = "tnum"
                        )
                    )
                    Text(
                        text = "道题",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = if (overview.dueReviewCount > 0) {
                        "来自 ${overview.dueLibraryCount} 个题库"
                    } else {
                        "今天没有到期的复习任务"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onPrimary.copy(alpha = 0.74f)
                )

                if (overview.dueReviewCount > 0) {
                    Spacer(modifier = Modifier.height(16.dp))
                    PressableSurface(
                        onClick = onReviewClick,
                        shape = RoundedCornerShape(13.dp),
                        color = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                        pressedScale = 0.97f
                    ) {
                        Row(
                            modifier = Modifier
                                .height(42.dp)
                                .padding(horizontal = 15.dp),
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "查看复习任务",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewStatsRow(overview: QuizLibraryOverview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OverviewStat(
            value = formatCount(overview.todayLearnedCount),
            label = "今日已学",
            modifier = Modifier.weight(1f)
        )
        OverviewStat(
            value = formatCount(overview.totalQuestionCount),
            label = "总题目",
            modifier = Modifier.weight(1f)
        )
        OverviewStat(
            value = formatCount(overview.libraryCount),
            label = "题库",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OverviewStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(72.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum"
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun LibrarySectionHeading(
    libraryCount: Int,
    hasLibraries: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp, top = 10.dp, end = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (hasLibraries) "题库进度" else "开始使用",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (hasLibraries) {
            Text(
                text = "$libraryCount 个题库",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuizLibraryCard(
    library: QuizLibrary,
    reviewCount: Int,
    masteryPercent: Int = 0,
    aiExplanationProgress: AiExplanationProgress = AiExplanationProgress(),
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val scale by animateFloatAsState(
        targetValue = if (animationsEnabled && isPressed) 0.98f else 1f,
        animationSpec = tween(PressDurationMillis, easing = StrongEaseOut),
        label = "libraryCardPressScale"
    )
    val normalizedMastery = masteryPercent.coerceIn(0, 100)
    val masteryProgress by animateFloatAsState(
        targetValue = normalizedMastery / 100f,
        animationSpec = if (animationsEnabled) {
            tween(260, easing = StrongEaseOut)
        } else {
            snap()
        },
        label = "libraryMasteryProgress"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) {
            colors.primaryContainer
        } else {
            colors.surfaceContainerLowest
        },
        animationSpec = tween(StateDurationMillis, easing = StrongEaseOut),
        label = "libraryCardContainerColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) colors.primary else colors.outlineVariant,
        animationSpec = tween(StateDurationMillis, easing = StrongEaseOut),
        label = "libraryCardBorderColor"
    )
    val titleColor by animateColorAsState(
        targetValue = if (isSelected) colors.onPrimaryContainer else colors.onSurface,
        animationSpec = tween(StateDurationMillis, easing = StrongEaseOut),
        label = "libraryCardTitleColor"
    )
    val shape = RoundedCornerShape(18.dp)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onLongClickLabel = "选择题库",
                onLongClick = onLongClick,
                onClick = onClick
            ),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                LibraryLeadingVisual(
                    library = library,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = library.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = titleColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatCount(library.quizCount)} 题",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) {
                                colors.onPrimaryContainer.copy(alpha = 0.72f)
                            } else {
                                colors.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.outline
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = if (reviewCount > 0) {
                                "$reviewCount 待复习"
                            } else {
                                "暂无待复习"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (reviewCount > 0) {
                                if (isSelected) colors.onPrimaryContainer else colors.primary
                            } else {
                                if (isSelected) {
                                    colors.onPrimaryContainer.copy(alpha = 0.72f)
                                } else {
                                    colors.onSurfaceVariant
                                }
                            },
                            fontWeight = if (reviewCount > 0) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                            maxLines = 1
                        )
                    }
                }
                if (!isSelectionMode) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }

            if (aiExplanationProgress.isGenerating) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AI 解析",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            colors.onPrimaryContainer.copy(alpha = 0.74f)
                        } else {
                            colors.onSurfaceVariant
                        }
                    )
                    Text(
                        text = "${aiExplanationProgress.cached}/${aiExplanationProgress.total}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFeatureSettings = "tnum"
                        ),
                        color = if (isSelected) colors.onPrimaryContainer else colors.primary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { aiExplanationProgress.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape),
                    color = if (isSelected) colors.onPrimaryContainer else colors.primary,
                    trackColor = if (isSelected) {
                        colors.onPrimaryContainer.copy(alpha = 0.18f)
                    } else {
                        colors.surfaceContainerHighest
                    }
                )
            }

            Spacer(modifier = Modifier.height(11.dp))
            LinearProgressIndicator(
                progress = { masteryProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(CircleShape),
                color = if (isSelected) colors.onPrimaryContainer else colors.primary,
                trackColor = if (isSelected) {
                    colors.onPrimaryContainer.copy(alpha = 0.18f)
                } else {
                    colors.surfaceContainerHighest
                },
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun LibraryLeadingVisual(
    library: QuizLibrary,
    isSelectionMode: Boolean,
    isSelected: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val tone = when (library.id.mod(3)) {
        0 -> colors.primaryContainer to colors.onPrimaryContainer
        1 -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Surface(
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        color = if (isSelectionMode) {
            colors.primary.copy(alpha = if (isSelected) 0.18f else 0.09f)
        } else {
            tone.first
        },
        contentColor = if (isSelectionMode) colors.primary else tone.second
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = null,
                    modifier = Modifier.size(24.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = colors.primary,
                        uncheckedColor = colors.onSurfaceVariant,
                        checkmarkColor = colors.onPrimary
                    )
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_bookmarks_24px),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

@Composable
private fun LibraryPickerDialog(
    purpose: LibraryPickerPurpose,
    libraries: List<QuizLibrary>,
    onDismiss: () -> Unit,
    onLibrarySelected: (QuizLibrary) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = purpose.dialogTitle,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = libraries,
                    key = { it.id }
                ) { library ->
                    PressableSurface(
                        onClick = { onLibrarySelected(library) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(15.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        pressedScale = 0.98f
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.icon_bookmarks_24px),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(21.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = library.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${formatCount(library.quizCount)} 题",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun PressableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: RoundedCornerShape,
    color: Color,
    contentColor: Color,
    pressedScale: Float,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val animationsEnabled = remember { ValueAnimator.areAnimatorsEnabled() }
    val scale by animateFloatAsState(
        targetValue = if (animationsEnabled && enabled && isPressed) pressedScale else 1f,
        animationSpec = tween(PressDurationMillis, easing = StrongEaseOut),
        label = "pressScale"
    )

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (enabled) 1f else 0.46f)
            .clip(shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        shape = shape,
        color = color,
        contentColor = contentColor,
        content = content
    )
}

@Composable
fun EmptyLibraryView(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .padding(horizontal = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.icon_bookmarks_24px),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "还没有题库",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = "点击底部“导入”，添加 Word、Excel 或文本题库",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun formatCount(value: Int): String {
    return NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)
}
