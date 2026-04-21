@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme

@Composable
fun CompanyLogosSection(
    title: String,
    companies: List<MetaCompany>,
    onCompanyClick: (MetaCompany) -> Unit = {}
) {
    if (companies.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NexioColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 48.dp)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 48.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(
                items = companies,
                key = { index, company ->
                    "$title-$index-${company.name}-${company.logo.orEmpty()}"
                }
            ) { _, company ->
                CompanyLogoCard(
                    company = company,
                    clickable = company.tmdbId != null,
                    onClick = { onCompanyClick(company) }
                )
            }
        }
    }
}

@Composable
private fun CompanyLogoCard(
    company: MetaCompany,
    clickable: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoWidthPx = remember(density) { with(density) { 140.dp.roundToPx() } }
    val logoHeightPx = remember(density) { with(density) { 56.dp.roundToPx() } }
    val logoModel = remember(context, company.logo, logoWidthPx, logoHeightPx) {
        company.logo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .size(width = logoWidthPx, height = logoHeightPx)
                .build()
        }
    }
    var logoLoadFailed by remember(company.logo) { mutableStateOf(false) }

    Card(
        onClick = {
            if (clickable) {
                onClick()
            }
        },
        modifier = Modifier
            .width(140.dp)
            .height(56.dp)
            .focusProperties {
                canFocus = clickable
            },
        colors = CardDefaults.colors(
            containerColor = NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.Secondary
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (logoModel != null && !logoLoadFailed) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = company.name,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    contentScale = ContentScale.Fit,
                    colorFilter = ColorFilter.colorMatrix(
                        ColorMatrix(
                            floatArrayOf(
                                -1f, 0f, 0f, 0f, 255f,
                                 0f, -1f, 0f, 0f, 255f,
                                 0f, 0f, -1f, 0f, 255f,
                                 0f, 0f, 0f, 1f, 0f
                            )
                        )
                    )
                )
            } else {
                Text(
                    text = company.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = NexioColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}
