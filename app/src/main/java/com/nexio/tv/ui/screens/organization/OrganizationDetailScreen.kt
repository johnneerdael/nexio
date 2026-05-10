package com.nexio.tv.ui.screens.organization

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nexio.tv.R
import com.nexio.tv.domain.model.TmdbOrganizationDetail
import com.nexio.tv.ui.components.GridContentCard
import com.nexio.tv.ui.components.PosterCardDefaults
import com.nexio.tv.ui.components.PosterCardStyle
import com.nexio.tv.ui.components.toRailCardData
import com.nexio.tv.ui.theme.NexioColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OrganizationDetailScreen(
    viewModel: OrganizationDetailViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    BackHandler { onBackPress() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
    ) {
        Crossfade(
            targetState = uiState,
            label = "OrganizationDetailStateCrossfade"
        ) { state ->
            when (state) {
                is OrganizationDetailUiState.Loading -> {
                    OrganizationDetailSkeleton(name = viewModel.entityName)
                }
                is OrganizationDetailUiState.Error -> {
                    OrganizationDetailError(
                        message = state.message,
                        onRetry = viewModel::retry
                    )
                }
                is OrganizationDetailUiState.Success -> {
                    OrganizationDetailContent(
                        detail = state.detail,
                        onNavigateToDetail = onNavigateToDetail
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OrganizationDetailContent(
    detail: TmdbOrganizationDetail,
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit
) {
    val posterCardStyle = remember {
        PosterCardStyle(
            width = 112.dp,
            height = 168.dp,
            cornerRadius = PosterCardDefaults.Style.cornerRadius,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OrganizationHeroSection(detail = detail)

        if (detail.titles.isNotEmpty()) {
            SectionHeader(
                title = stringResource(R.string.organization_detail_titles),
                count = detail.totalResults.takeIf { it > 0 } ?: detail.titles.size
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(
                    items = detail.titles,
                    key = { _, item -> item.id }
                ) { _, item ->
                    GridContentCard(
                        item = item.toRailCardData(),
                        posterCardStyle = posterCardStyle,
                        onClick = { onNavigateToDetail(item.id, item.apiType, null) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OrganizationHeroSection(detail: TmdbOrganizationDetail) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, top = 32.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        OrganizationLogo(detail = detail)
        Spacer(modifier = Modifier.width(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = detail.name,
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = NexioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(20.dp))
            val metadata = buildList {
                detail.originCountry?.let {
                    add(stringResource(R.string.organization_detail_country, it))
                }
                detail.headquarters?.let {
                    add(stringResource(R.string.organization_detail_headquarters, it))
                }
                detail.parentCompanyName?.let {
                    add(stringResource(R.string.organization_detail_parent_company, it))
                }
                detail.homepage?.let {
                    add(stringResource(R.string.organization_detail_homepage, it))
                }
            }
            metadata.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            detail.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextSecondary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OrganizationLogo(detail: TmdbOrganizationDetail) {
    Card(
        onClick = {},
        modifier = Modifier
            .width(180.dp)
            .height(180.dp)
            .focusable(false),
        shape = CardDefaults.shape(shape = RoundedCornerShape(16.dp)),
        colors = CardDefaults.colors(
            containerColor = NexioColors.SurfaceVariant,
            focusedContainerColor = NexioColors.SurfaceVariant
        ),
        border = CardDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, NexioColors.Border),
                shape = RoundedCornerShape(16.dp)
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(androidx.compose.ui.graphics.Color.White),
            contentAlignment = Alignment.Center
        ) {
            val logo = detail.logo
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logo)
                        .crossfade(true)
                        .size(
                            width = with(LocalDensity.current) { 150.dp.roundToPx() },
                            height = with(LocalDensity.current) { 150.dp.roundToPx() }
                        )
                        .build(),
                    contentDescription = detail.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text(
                    text = detail.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NexioColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = NexioColors.TextPrimary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(NexioColors.SurfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = NexioColors.TextSecondary
            )
        }
    }
}

@Composable
private fun OrganizationDetailSkeleton(name: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text(
            text = name.ifBlank { stringResource(R.string.organization_detail_loading) },
            style = MaterialTheme.typography.displayMedium,
            color = NexioColors.TextPrimary
        )
        Spacer(modifier = Modifier.height(16.dp))
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(22.dp)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NexioColors.SurfaceVariant)
            )
        }
    }
}

@Composable
private fun OrganizationDetailError(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.organization_detail_error),
                style = MaterialTheme.typography.headlineMedium,
                color = NexioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = NexioColors.TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.SurfaceVariant,
                    focusedContainerColor = NexioColors.Surface
                )
            ) {
                Text(stringResource(R.string.organization_detail_retry))
            }
        }
    }
}
