package com.opentune.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.opentune.ui.theme.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opentune.data.radio.Radio
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface RadioFilter {
    data object Favourites : RadioFilter
    data object Popular : RadioFilter
    data object Nearby : RadioFilter
    data class Country(val code: String) : RadioFilter
    data class Genre(val tag: String) : RadioFilter
}

private data class RadioPlace(val country: String, val city: String?, val state: String?, val coordinates: Pair<Double, Double>?)
private data class FoundStation(val station: Radio.Station, val distance: Double? = null)

@Composable
internal fun RadioPage() {
    val favourites by Radio.favourites.collectAsState()
    val recent by Radio.recent.collectAsState()
    val queue by Player.queue.collectAsState()
    val playingIndex by Player.index.collectAsState()
    val playingId = queue.getOrNull(playingIndex)?.videoId
    val country = remember { Locale.getDefault().country.takeIf { it.length == 2 } }
    val countryName = remember(country) { country?.let { Locale.Builder().setRegion(it).build().displayCountry } }
    var filter by remember { mutableStateOf<RadioFilter>(if (favourites.isEmpty()) RadioFilter.Popular else RadioFilter.Favourites) }
    var query by remember { mutableStateOf("") }
    var place by remember { mutableStateOf<RadioPlace?>(null) }
    var found by remember { mutableStateOf<Result<List<FoundStation>>?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var addStation by remember { mutableStateOf(false) }
    var choosePlace by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(query, filter, place, attempt) {
        found = null
        val text = query.trim()
        if (text.isEmpty() && (filter == RadioFilter.Favourites || filter == RadioFilter.Nearby && place == null)) return@LaunchedEffect
        if (text.isNotEmpty()) delay(350)
        found = try {
            val stations = if (text.isNotEmpty()) Radio.search(text).map(::FoundStation) else when (val selected = filter) {
                RadioFilter.Favourites -> emptyList()
                RadioFilter.Popular -> Radio.popular().map(::FoundStation)
                is RadioFilter.Country -> Radio.inCountry(selected.code).map(::FoundStation)
                is RadioFilter.Genre -> Radio.byTag(selected.tag).map(::FoundStation)
                RadioFilter.Nearby -> {
                    val location = checkNotNull(place)
                    val near = location.coordinates?.let { (lat, lon) -> Radio.nearby(lat, lon) }
                        .orEmpty().map { FoundStation(it.station, it.distanceKm) }
                    val listed = Radio.inArea(location.country, location.city, location.state).map(::FoundStation)
                    (near + listed).distinctBy { it.station.uuid }
                }
            }
            Result.success(stations)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    ScrollPage("Radio", subtitle = "Live internet stations", spacing = 10.dp, trailing = {
        Button(onClick = { addStation = true }) {
            Icon(Icons.Rounded.Add, null)
            Text("Add station", Modifier.padding(start = 6.dp))
        }
    }) {
        item { DiscoverySearch(query, { query = it }, "Search stations") }
        if (query.isBlank()) {
            item(key = "station-filters") {
                HRow(8.dp) {
                    item { FilterChip(filter == RadioFilter.Favourites, { filter = RadioFilter.Favourites }, { Text("Favourites") }) }
                    item { FilterChip(filter == RadioFilter.Popular, { filter = RadioFilter.Popular }, { Text("Popular") }) }
                    item {
                        FilterChip(filter == RadioFilter.Nearby, {
                            filter = RadioFilter.Nearby
                            if (place == null) choosePlace = true
                        }, { Text("Nearby") })
                    }
                    if (country != null && countryName != null) {
                        item { FilterChip(filter == RadioFilter.Country(country), { filter = RadioFilter.Country(country) }, { Text(countryName) }) }
                    }
                    items(Radio.GENRES) { genre ->
                        val selected = RadioFilter.Genre(genre)
                        FilterChip(filter == selected, { filter = selected }, { Text(genre.replaceFirstChar { it.titlecase() }) })
                    }
                }
            }
        }
        if (query.isBlank() && filter == RadioFilter.Favourites) {
            if (favourites.isEmpty()) item { DiscoveryNotice("Favourite stations appear here when you tap their heart.") }
            items(favourites, key = { "fav:${it.uuid}" }) { station ->
                StationRow(FoundStation(station), isFavourite = true, isCurrent = playingId == station.id)
            }
            val recentlyPlayed = recent.filterNot { station -> favourites.any { it.uuid == station.uuid } }
            if (recentlyPlayed.isNotEmpty()) item { DiscoveryTitle("Recently played") }
            items(recentlyPlayed, key = { "recent:${it.uuid}" }) { station ->
                StationRow(FoundStation(station), isFavourite = false, isCurrent = playingId == station.id)
            }
        } else {
            val byLocation = query.isBlank() && filter == RadioFilter.Nearby
            if (byLocation) item {
                TextButton(onClick = { choosePlace = true }, modifier = Modifier.padding(horizontal = GUTTER)) {
                    Text(if (place == null) "Choose a location" else "Change location")
                }
            }
            if (!byLocation || place != null) {
                val result = found
                val stations = result?.getOrNull().orEmpty()
                when {
                    result == null -> item { Loading() }
                    result.isFailure -> item { DiscoveryNotice("Couldn't load stations", retry = { attempt++ }) }
                    stations.isEmpty() -> item { DiscoveryNotice("No stations found") }
                    else -> items(stations, key = { it.station.uuid }) { match ->
                        StationRow(match, favourites.any { it.uuid == match.station.uuid }, playingId == match.station.id, byLocation)
                    }
                }
            }
        }
        item(key = "suggest-station") {
            TextButton(onClick = { uriHandler.openUri("https://www.radio-browser.info/add") }, modifier = Modifier.padding(horizontal = GUTTER)) {
                Text("Suggest a station to Radio Browser")
            }
        }
    }

    if (addStation) AddRadioStation(onClose = { addStation = false }) { name, url ->
        Radio.addCustom(name, url)
        addStation = false
        query = ""
        filter = RadioFilter.Favourites
    }
    if (choosePlace) RadioLocation(place, country, onClose = { choosePlace = false }) {
        place = it
        choosePlace = false
    }
}

@Composable
private fun StationRow(match: FoundStation, isFavourite: Boolean, isCurrent: Boolean, showDistance: Boolean = false) {
    val station = match.station
    Row(
        Modifier.fillMaxWidth().clickable {
            Radio.played(station)
            Player.playAll(listOf(station.toSong()), 0)
        }.padding(horizontal = GUTTER, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Cover(station.favicon, 48.dp, RoundedCornerShape(8.dp))
        Column(Modifier.weight(1f)) {
            Text(station.name, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (showDistance) station.nearbyDetails(match.distance) else station.details,
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { Radio.setFavourite(station, !isFavourite) }) {
            Icon(if (isFavourite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isFavourite) "Remove favourite" else "Add favourite")
        }
    }
}

@Composable
private fun AddRadioStation(onClose: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add radio station") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Station name") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("HTTP or HTTPS stream URL") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && Radio.isStreamUrl(url), onClick = { onAdd(name, url) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun RadioLocation(place: RadioPlace?, defaultCountry: String?, onClose: () -> Unit, onSearch: (RadioPlace) -> Unit) {
    var country by remember { mutableStateOf(place?.country ?: defaultCountry.orEmpty()) }
    var city by remember { mutableStateOf(place?.city.orEmpty()) }
    var state by remember { mutableStateOf(place?.state.orEmpty()) }
    var latitude by remember { mutableStateOf(place?.coordinates?.first?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(place?.coordinates?.second?.toString().orEmpty()) }
    val lat = latitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lon = longitude.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val coordinates = if (lat != null && lon != null) lat to lon else null
    val area = country.matches(Regex("[a-zA-Z]{2}")) && (city.isNotBlank() || state.isNotBlank())
    val valid = coordinates != null || latitude.isBlank() && longitude.isBlank() && area
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Stations nearby") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("Enter a city or region with its country code, or enter map coordinates. Coordinates are rounded before the search.")
                OutlinedTextField(country, { country = it }, label = { Text("Country code (for example CH)") }, singleLine = true)
                OutlinedTextField(city, { city = it }, label = { Text("City") }, singleLine = true)
                OutlinedTextField(state, { state = it }, label = { Text("State or region") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(latitude, { latitude = it }, Modifier.weight(1f), label = { Text("Latitude") }, singleLine = true)
                    OutlinedTextField(longitude, { longitude = it }, Modifier.weight(1f), label = { Text("Longitude") }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSearch(RadioPlace(country.uppercase(Locale.ROOT), city.trim().ifBlank { null }, state.trim().ifBlank { null }, coordinates))
            }) { Text("Find stations") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
internal fun DiscoverySearch(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth().padding(horizontal = GUTTER), label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear search") } },
        singleLine = true,
    )
}

@Composable
internal fun DiscoveryTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = GUTTER, end = GUTTER, top = 12.dp))
}

@Composable
internal fun DiscoveryNotice(message: String, retry: (() -> Unit)? = null) {
    Row(Modifier.padding(horizontal = GUTTER, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        retry?.let { TextButton(onClick = it) { Text("Try again") } }
    }
}
