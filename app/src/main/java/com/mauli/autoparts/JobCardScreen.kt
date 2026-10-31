package com.mauli.autoparts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Assignment
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Motorcycle
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** The job card list. */
data object JobCardsPage : Screen(), LeafScreen

/** One job card. id 0 = a new one. */
data class JobCardPage(val id: Long) : Screen(), LeafScreen

/** Job cards are saved in the bills table with this kind, so they travel with every backup. */
const val JOB_KIND = 5

val JobStatuses = listOf("Received", "Working", "Ready", "Delivered")
private val JobFilters = listOf("Open") + JobStatuses + "All"

val Bill.jobStatus: String get() = ref.ifBlank { JobStatuses.first() }
val Bill.jobOpen: Boolean get() = jobStatus != "Delivered"

fun jobColor(status: String): Color = when (status) {
    "Working" -> Amber
    "Ready" -> Green
    "Delivered" -> Muted
    else -> Navy
}

/** "Today" / "2 days in shop" */
private fun inShopText(ms: Long): String {
    val days = ((now() - ms) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Today"
        days == 1 -> "1 day in shop"
        else -> "$days days in shop"
    }
}

private fun filterJobs(list: List<Bill>, query: String, filter: String): List<Bill> {
    val base = when (filter) {
        "Open" -> list.filter { it.jobOpen }
        "All" -> list
        else -> list.filter { it.jobStatus == filter }
    }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return base
    return base.filter { b ->
        val hay = "${b.customer} ${b.phone} ${b.vehicleNo} ${vehicleLabel(b.vehicleNo)} ${b.model} ${b.note} ${b.payMode} ${billCode(b)}".lowercase()
        words.all { it in hay }
    }
}

/** Opens Billing with this job card's customer, vehicle and complaint already filled in. */
private fun jobToBill(vm: AppViewModel, card: Bill) {
    val blank = vm.drafts.indexOfFirst {
        it.editId == 0L && it.lines.isEmpty() && it.customer.isBlank() && it.vehicleNo.isBlank() && it.phone.isBlank()
    }
    if (blank >= 0) vm.current = blank else vm.newDraft()
    vm.editDraft {
        it.copy(
            customer = card.customer, phone = card.phone, vehicleNo = card.vehicleNo,
            model = card.model, km = card.km, note = card.note
        )
    }
    vm.tab(Screen.Billing)
    vm.toast(
        if (card.paid > 0.009) "Advance ${rs(card.paid)} already taken — adjust the payment"
        else "Add the parts and labour, then save the bill"
    )
}

private fun jobMessage(shop: String, card: Bill): String = buildString {
    append("Namaskar")
    if (card.customer.isNotBlank()) append(" ").append(card.customer)
    append(",\n*").append(shop).append("*\n")
    if (card.vehicleNo.isNotBlank()) append("Vehicle: ").append(vehicleLabel(card.vehicleNo)).append("\n")
    append("Job card: ").append(billCode(card)).append("\n")
    when (card.jobStatus) {
        "Ready" -> append("Your vehicle is *ready*. Please collect it.\n")
        "Delivered" -> append("Your vehicle has been delivered. Thank you!\n")
        "Working" -> append("Work on your vehicle is going on.\n")
        else -> append("Your vehicle has been received for work.\n")
    }
    if (card.total > 0.009) append("Estimate: ").append(rs(card.total)).append("\n")
    if (card.paid > 0.009) append("Advance received: ").append(rs(card.paid)).append("\n")
    append("Thank you 🙏")
}

// ================= Job card list =================

@Composable
fun JobCardsScreen(vm: AppViewModel) {
    val cards by remember { vm.dao.jobCardsFlow() }.collectAsState(initial = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Open") }
    val shown = remember(cards, query, filter) { filterJobs(cards, query, filter) }
    val open = cards.count { it.jobOpen }
    val ready = cards.count { it.jobStatus == "Ready" }

    fun advance(b: Bill) {
        val i = JobStatuses.indexOf(b.jobStatus)
        if (i < 0 || i >= JobStatuses.lastIndex) {
            vm.toast("Already delivered • open the card to change it")
            return
        }
        val next = JobStatuses[i + 1]
        vm.viewModelScope.launch {
            vm.dao.updateBill(b.copy(ref = next))
            vm.toast("${billCode(b)} → $next")
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Job Cards",
            subtitle = "$open open  •  $ready ready to deliver",
            onBack = { vm.back() },
            actions = { HeaderAction(Icons.Rounded.Add, "New job card") { vm.open(JobCardPage(0)) } },
            bottom = {
                SearchBox(query, { query = it }, "Search vehicle, customer, complaint…")
                Spacer(Modifier.height(10.dp))
                ChipRow(JobFilters, filter, { filter = it }, onDark = true)
            }
        )
        if (shown.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EmptyState(
                    Icons.Rounded.Assignment,
                    if (cards.isEmpty()) "No job cards yet" else "Nothing here",
                    if (cards.isEmpty()) "Make a job card when a vehicle comes in. Note the complaint, track the work, then turn it into a bill."
                    else "No job card in this list right now.",
                    action = if (cards.isEmpty()) "Make first job card" else null
                ) { vm.open(JobCardPage(0)) }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(shown, key = { it.id }) { b ->
                    JobCardRow(b, onOpen = { vm.open(JobCardPage(b.id)) }, onStatus = { advance(b) })
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            MainButton("New job card", Modifier.fillMaxWidth(), icon = Icons.Rounded.Add, color = Orange) {
                vm.open(JobCardPage(0))
            }
        }
    }
}

@Composable
private fun JobCardRow(b: Bill, onOpen: () -> Unit, onStatus: () -> Unit) {
    val status = b.jobStatus
    val color = jobColor(status)
    AppCard(Modifier.fillMaxWidth(), onClick = onOpen, padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Assignment, color, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    vehicleLabel(b.vehicleNo).ifBlank { b.customer.ifBlank { "Vehicle" } },
                    color = Ink, fontSize = 15.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOf(b.customer, b.model).filter { it.isNotBlank() }.joinToString("  •  ").ifBlank { billCode(b) },
                    color = Muted, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("${billCode(b)}  •  ${inShopText(b.date)}", color = Faint, fontSize = 11.5.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(status, color, onStatus)
                if (b.total > 0.009) {
                    Spacer(Modifier.height(4.dp))
                    Text("Est. ${rs(b.total)}", color = Muted, fontSize = 12.sp)
                }
            }
        }
        if (b.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(b.note, color = Muted, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (b.payMode.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text("Mechanic: ${b.payMode}", color = Faint, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Status label. Tapping it moves the job card to the next stage. */
@Composable
private fun StatusChip(status: String, color: Color, onClick: () -> Unit) {
    Text(
        status,
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1
    )
}

// ================= New / edit job card =================

@Composable
fun JobCardEditScreen(vm: AppViewModel, id: Long) {
    val context = LocalContext.current
    val isNew = id == 0L
    var loaded by remember { mutableStateOf(isNew) }
    var original by remember { mutableStateOf<Bill?>(null) }
    var vehicle by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var customer by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var km by remember { mutableStateOf("") }
    var complaint by remember { mutableStateOf("") }
    var mechanic by remember { mutableStateOf("") }
    var estimate by remember { mutableStateOf("") }
    var advance by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(JobStatuses.first()) }
    var askDelete by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (!isNew) {
            vm.dao.bill(id)?.let { b ->
                original = b
                vehicle = b.vehicleNo
                phone = b.phone
                customer = b.customer
                model = b.model
                km = b.km
                complaint = b.note
                mechanic = b.payMode
                estimate = if (b.total > 0) num(b.total) else ""
                advance = if (b.paid > 0) num(b.paid) else ""
                status = b.jobStatus
            }
            loaded = true
        }
    }

    val vehKey = normVehicle(vehicle)

    // An old customer's name, mobile and model fill in by themselves.
    LaunchedEffect(vehKey) {
        if (isNew && vehKey.length >= 8) {
            vm.dao.lastBillOf(vehKey, "")?.let { b ->
                if (customer.isBlank()) customer = b.customer
                if (phone.isBlank()) phone = b.phone
                if (model.isBlank()) model = b.model
            }
        }
    }

    fun save(onDone: (Bill) -> Unit) {
        if (vehKey.isBlank() && customer.isBlank() && phone.isBlank()) {
            vm.toast("Add the vehicle number or the customer name")
            return
        }
        vm.viewModelScope.launch {
            try {
                val old = original
                val card = Bill(
                    id = old?.id ?: 0L,
                    billNo = old?.billNo ?: vm.dao.nextJobNo(),
                    kind = JOB_KIND,
                    date = old?.date ?: now(),
                    customer = customer.trim(),
                    phone = phone.trim(),
                    vehicleNo = vehKey,
                    model = model.trim(),
                    km = km.trim(),
                    total = estimate.toNum(),
                    paid = advance.toNum(),
                    payMode = mechanic.trim(),
                    note = complaint.trim(),
                    ref = status
                )
                val saved = if (old == null) card.copy(id = vm.dao.insertBill(card)) else {
                    vm.dao.updateBill(card)
                    card
                }
                original = saved
                vm.toast(if (old == null) "Job card ${billCode(saved)} saved ✓" else "Job card updated ✓")
                onDone(saved)
            } catch (e: Exception) {
                vm.toast("Could not save the job card")
            }
        }
    }

    fun remove() {
        val old = original ?: return
        vm.viewModelScope.launch {
            vm.dao.deleteItems(old.id)
            vm.dao.deleteBill(old.id)
            vm.toast("Job card deleted")
            vm.back()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = if (isNew) "New Job Card" else (original?.let { billCode(it) } ?: "Job Card"),
            subtitle = if (isNew) "Vehicle taken in for work" else original?.let { "${it.jobStatus}  •  ${whenText(it.date)}" },
            onBack = { vm.back() },
            actions = {
                if (!isNew) {
                    HeaderAction(Icons.Rounded.Forum, "Tell the customer") {
                        original?.let { Printer.whatsappText(context, it.phone, jobMessage(vm.settings.shopName.ifBlank { "Mauli Auto Parts" }, it)) }
                    }
                    HeaderAction(Icons.Rounded.Delete, "Delete job card") { askDelete = true }
                }
            }
        )
        if (!loaded) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Navy)
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                JCard("Vehicle & customer", Icons.Rounded.Motorcycle, Navy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppField(
                            vehicle, { vehicle = it.uppercase() }, "Vehicle no.",
                            Modifier.weight(1f), caps = KeyboardCapitalization.Characters
                        )
                        AppField(
                            phone, { phone = it.filter { c -> c.isDigit() }.take(10) }, "Mobile",
                            Modifier.weight(1f), type = KeyboardType.Phone
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    AppField(customer, { customer = it }, "Customer name")
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppField(model, { model = it }, "Model", Modifier.weight(1.4f))
                        AppField(
                            km, { km = it.filter { c -> c.isDigit() }.take(7) }, "KM reading",
                            Modifier.weight(1f), type = KeyboardType.Number
                        )
                    }
                    if (vehKey.length >= 6) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "See all visits of this vehicle →",
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.open(VehiclePage(vehKey)) }
                                .padding(vertical = 4.dp),
                            color = Purple, fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))

                JCard("Work to do", Icons.Rounded.Build, Orange) {
                    AppField(
                        complaint, { complaint = it }, "Complaint / work asked by customer",
                        caps = KeyboardCapitalization.Sentences, singleLine = false
                    )
                    Spacer(Modifier.height(10.dp))
                    AppField(mechanic, { mechanic = it }, "Mechanic working on it (optional)")
                    Spacer(Modifier.height(14.dp))
                    Text("Status", color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Segmented(JobStatuses, status, { status = it }, accent = jobColor(status))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (status) {
                            "Working" -> "Work is going on."
                            "Ready" -> "Vehicle is ready. Tell the customer to collect it."
                            "Delivered" -> "Vehicle handed over to the customer."
                            else -> "Vehicle is in the shop, work not started."
                        },
                        color = jobColor(status), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(14.dp))

                JCard("Estimate & advance", Icons.Rounded.Payment, Green) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppField(
                            estimate, { estimate = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                            "Estimate told to customer", Modifier.weight(1f), type = KeyboardType.Decimal, prefix = "₹ "
                        )
                        AppField(
                            advance, { advance = it.filter { c -> c.isDigit() || c == '.' }.take(10) },
                            "Advance taken", Modifier.weight(1f), type = KeyboardType.Decimal, prefix = "₹ ",
                            imeAction = ImeAction.Done
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Both are optional. The real amount comes from the bill you make later.",
                        color = Faint, fontSize = 12.sp
                    )
                    if (!isNew) {
                        Spacer(Modifier.height(12.dp))
                        LightButton("Repeat last visit's items", Modifier.fillMaxWidth(), icon = Icons.Rounded.History, color = Navy) {
                            val card = original
                            if (card == null) vm.toast("Save the job card first")
                            else vm.viewModelScope.launch {
                                val last = vm.dao.lastBillOf(card.vehicleNo, card.phone)
                                if (last == null) vm.toast("No old bill found for this vehicle")
                                else {
                                    jobToBill(vm, card)
                                    Catalog.addLines(vm, vm.dao.items(last.id), "Last visit")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isNew) {
                    MainButton("Save job card", Modifier.fillMaxWidth(), icon = Icons.Rounded.Check, color = Orange) {
                        save { vm.back() }
                    }
                } else {
                    LightButton("Save", Modifier.weight(1f), icon = Icons.Rounded.Check, color = Navy) { save { vm.back() } }
                    MainButton("Make bill", Modifier.weight(1f), icon = Icons.Rounded.PointOfSale, color = Orange) {
                        save { card -> jobToBill(vm, card) }
                    }
                }
            }
        }
    }

    if (askDelete) {
        ConfirmDialog(
            title = "Delete this job card?",
            text = "The job card will be removed. Bills already made are not touched.",
            confirm = "Delete",
            danger = true,
            onConfirm = { remove() },
            onDismiss = { askDelete = false }
        )
    }
}

@Composable
private fun JCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    AppCard(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tint, 34.dp)
            Spacer(Modifier.width(10.dp))
            Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}
