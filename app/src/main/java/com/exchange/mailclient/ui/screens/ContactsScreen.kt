package com.exchange.mailclient.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.exchange.mailclient.data.database.ContactEntity
import com.exchange.mailclient.data.database.ContactSource
import com.exchange.mailclient.data.repository.AccountRepository
import com.exchange.mailclient.data.repository.ContactRepository
import com.exchange.mailclient.eas.EasResult
import com.exchange.mailclient.eas.GalContact
import com.exchange.mailclient.ui.LocalLanguage
import com.exchange.mailclient.ui.AppLanguage
import com.exchange.mailclient.ui.Strings
import com.exchange.mailclient.ui.theme.LocalColorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Цвета для аватаров
private val avatarColors = listOf(
    Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFF3949AB),
    Color(0xFF1E88E5), Color(0xFF00ACC1), Color(0xFF43A047),
    Color(0xFFFFB300), Color(0xFFF4511E), Color(0xFF6D4C41)
)

private fun getAvatarColor(name: String): Color {
    if (name.isBlank()) return avatarColors[0]
    val hash = name.lowercase().hashCode()
    return avatarColors[(hash and 0x7FFFFFFF) % avatarColors.size]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBackClick: () -> Unit,
    onComposeClick: (String) -> Unit // email для нового письма
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactRepo = remember { ContactRepository(context) }
    val accountRepo = remember { AccountRepository(context) }
    val clipboardManager = LocalClipboardManager.current
    val isRussian = LocalLanguage.current == AppLanguage.RUSSIAN

    val activeAccount by accountRepo.activeAccount.collectAsState(initial = null)
    val accountId = activeAccount?.id ?: 0L
    
    // Вкладки
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(Strings.personalContacts, Strings.organization)
    
    // Личные контакты
    val localContacts by contactRepo.getContacts(accountId).collectAsState(initial = emptyList())
    var localSearchQuery by rememberSaveable { mutableStateOf("") }
    var filteredLocalContacts by remember { mutableStateOf<List<ContactEntity>>(emptyList()) }
    
    // GAL контакты
    var galSearchQuery by rememberSaveable { mutableStateOf("") }
    var galContacts by remember { mutableStateOf<List<GalContact>>(emptyList()) }
    var isSearchingGal by remember { mutableStateOf(false) }
    var galError by remember { mutableStateOf<String?>(null) }
    
    // Диалоги
    var showAddDialog by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<ContactEntity?>(null) }
    var showDeleteDialog by remember { mutableStateOf<ContactEntity?>(null) }
    var showContactDetails by remember { mutableStateOf<Any?>(null) } // ContactEntity или GalContact
    var showMoreMenu by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    
    // Импорт
    val importedMessage = if (isRussian) "Импортировано контактов:" else "Imported contacts:"
    val contactSavedMsg = if (isRussian) "Контакт сохранён" else "Contact saved"
    val contactDeletedMsg = if (isRussian) "Контакт удалён" else "Contact deleted"
    val emailCopiedMsg = if (isRussian) "Email скопирован" else "Email copied"
    val noContactsToExportMsg = if (isRussian) "Нет контактов для экспорта" else "No contacts to export"
    
    val importVCardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val content = inputStream?.bufferedReader()?.readText() ?: ""
                    inputStream?.close()
                    val count = contactRepo.importFromVCard(accountId, content)
                    Toast.makeText(context, "$importedMessage $count", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    val importCSVLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val inputStream = context.contentResolver.openInputStream(it)
                    val content = inputStream?.bufferedReader()?.readText() ?: ""
                    inputStream?.close()
                    val count = contactRepo.importFromCSV(accountId, content)
                    Toast.makeText(context, "$importedMessage $count", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    // Фильтрация локальных контактов
    LaunchedEffect(localContacts, localSearchQuery) {
        filteredLocalContacts = if (localSearchQuery.isBlank()) {
            localContacts
        } else {
            localContacts.filter {
                it.displayName.contains(localSearchQuery, true) ||
                it.email.contains(localSearchQuery, true) ||
                it.company.contains(localSearchQuery, true)
            }
        }
    }
    
    // Поиск в GAL с debounce
    LaunchedEffect(galSearchQuery) {
        if (galSearchQuery.length >= 2) {
            kotlinx.coroutines.delay(500) // debounce
            isSearchingGal = true
            galError = null
            when (val result = contactRepo.searchGAL(accountId, galSearchQuery)) {
                is EasResult.Success -> galContacts = result.data
                is EasResult.Error -> galError = result.message
            }
            isSearchingGal = false
        } else {
            galContacts = emptyList()
        }
    }
    
    // Группировка по алфавиту
    val groupedContacts = remember(filteredLocalContacts) {
        filteredLocalContacts
            .sortedBy { it.displayName.lowercase() }
            .groupBy { it.displayName.firstOrNull()?.uppercaseChar() ?: '#' }
    }

    
    // Диалог добавления/редактирования контакта
    if (showAddDialog || editingContact != null) {
        ContactEditDialog(
            contact = editingContact,
            onDismiss = { 
                showAddDialog = false
                editingContact = null
            },
            onSave = { displayName, email, firstName, lastName, phone, mobilePhone, workPhone, company, department, jobTitle, notes ->
                scope.launch {
                    if (editingContact != null) {
                        contactRepo.updateContact(editingContact!!.copy(
                            displayName = displayName,
                            email = email,
                            firstName = firstName,
                            lastName = lastName,
                            phone = phone,
                            mobilePhone = mobilePhone,
                            workPhone = workPhone,
                            company = company,
                            department = department,
                            jobTitle = jobTitle,
                            notes = notes
                        ))
                    } else {
                        contactRepo.addContact(
                            accountId = accountId,
                            displayName = displayName,
                            email = email,
                            firstName = firstName,
                            lastName = lastName,
                            phone = phone,
                            mobilePhone = mobilePhone,
                            workPhone = workPhone,
                            company = company,
                            department = department,
                            jobTitle = jobTitle,
                            notes = notes
                        )
                    }
                    Toast.makeText(context, contactSavedMsg, Toast.LENGTH_SHORT).show()
                }
                showAddDialog = false
                editingContact = null
            }
        )
    }
    
    // Диалог удаления
    showDeleteDialog?.let { contact ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(Strings.deleteContact) },
            text = { Text(Strings.deleteContactConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        contactRepo.deleteContact(contact.id)
                        Toast.makeText(context, contactDeletedMsg, Toast.LENGTH_SHORT).show()
                    }
                    showDeleteDialog = null
                }) {
                    Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(Strings.cancel)
                }
            }
        )
    }
    
    // Диалог деталей контакта
    showContactDetails?.let { contact ->
        ContactDetailsDialog(
            contact = contact,
            onDismiss = { showContactDetails = null },
            onWriteEmail = { email ->
                showContactDetails = null
                onComposeClick(email)
            },
            onCopyEmail = { email ->
                clipboardManager.setText(AnnotatedString(email))
                Toast.makeText(context, emailCopiedMsg, Toast.LENGTH_SHORT).show()
            },
            onCall = { phone ->
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                context.startActivity(intent)
            },
            onEdit = {
                if (contact is ContactEntity) {
                    showContactDetails = null
                    editingContact = contact
                }
            },
            onDelete = {
                if (contact is ContactEntity) {
                    showContactDetails = null
                    showDeleteDialog = contact
                }
            },
            onAddToContacts = {
                if (contact is GalContact) {
                    scope.launch {
                        contactRepo.addContact(
                            accountId = accountId,
                            displayName = contact.displayName,
                            email = contact.email,
                            firstName = contact.firstName,
                            lastName = contact.lastName,
                            phone = contact.phone,
                            mobilePhone = contact.mobilePhone,
                            company = contact.company,
                            department = contact.department,
                            jobTitle = contact.jobTitle
                        )
                        Toast.makeText(context, contactSavedMsg, Toast.LENGTH_SHORT).show()
                    }
                    showContactDetails = null
                }
            }
        )
    }
    
    // Диалог экспорта
    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onExportVCard = {
                scope.launch {
                    val contacts = if (selectedTab == 0) filteredLocalContacts else emptyList()
                    if (contacts.isEmpty() && selectedTab == 0) {
                        Toast.makeText(context, noContactsToExportMsg, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val vcardData = if (selectedTab == 0) {
                        contactRepo.exportToVCard(contacts)
                    } else {
                        contactRepo.exportGalToVCard(galContacts)
                    }
                    shareFile(context, vcardData, "contacts.vcf", "text/vcard")
                }
                showExportDialog = false
            },
            onExportCSV = {
                scope.launch {
                    val contacts = if (selectedTab == 0) filteredLocalContacts else emptyList()
                    if (contacts.isEmpty() && selectedTab == 0) {
                        Toast.makeText(context, noContactsToExportMsg, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val csvData = if (selectedTab == 0) {
                        contactRepo.exportToCSV(contacts)
                    } else {
                        contactRepo.exportGalToCSV(galContacts)
                    }
                    shareFile(context, csvData, "contacts.csv", "text/csv")
                }
                showExportDialog = false
            }
        )
    }

    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.contacts, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, Strings.back, tint = Color.White)
                    }
                },
                actions = {
                    // Добавить контакт (только для личных)
                    if (selectedTab == 0) {
                        IconButton(onClick = { showAddDialog = true }) {
                            Icon(Icons.Default.PersonAdd, Strings.addContact, tint = Color.White)
                        }
                    }
                    // Меню
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Default.MoreVert, null, tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(Strings.exportContacts) },
                                onClick = {
                                    showMoreMenu = false
                                    showExportDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Upload, null) }
                            )
                            if (selectedTab == 0) {
                                DropdownMenuItem(
                                    text = { Text(Strings.importFromVCard) },
                                    onClick = {
                                        showMoreMenu = false
                                        importVCardLauncher.launch("text/vcard")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Download, null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(Strings.importFromCSV) },
                                    onClick = {
                                        showMoreMenu = false
                                        importCSVLauncher.launch("text/*")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Download, null) }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                modifier = Modifier.background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            LocalColorTheme.current.gradientStart,
                            LocalColorTheme.current.gradientEnd
                        )
                    )
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Вкладки
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }
            
            // Поле поиска
            OutlinedTextField(
                value = if (selectedTab == 0) localSearchQuery else galSearchQuery,
                onValueChange = {
                    if (selectedTab == 0) localSearchQuery = it else galSearchQuery = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text(Strings.searchContacts) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    val query = if (selectedTab == 0) localSearchQuery else galSearchQuery
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            if (selectedTab == 0) localSearchQuery = "" else galSearchQuery = ""
                        }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                singleLine = true
            )
            
            // Контент
            when (selectedTab) {
                0 -> PersonalContactsList(
                    groupedContacts = groupedContacts,
                    onContactClick = { showContactDetails = it }
                )
                1 -> OrganizationContactsList(
                    query = galSearchQuery,
                    contacts = galContacts,
                    isSearching = isSearchingGal,
                    error = galError,
                    onContactClick = { showContactDetails = it }
                )
            }
        }
    }
}


@Composable
private fun PersonalContactsList(
    groupedContacts: Map<Char, List<ContactEntity>>,
    onContactClick: (ContactEntity) -> Unit
) {
    if (groupedContacts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.People,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    Strings.noContacts,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            groupedContacts.forEach { (letter, contacts) ->
                item {
                    Text(
                        text = letter.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                items(contacts, key = { it.id }) { contact ->
                    ContactItem(
                        name = contact.displayName,
                        email = contact.email,
                        company = contact.company,
                        onClick = { onContactClick(contact) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OrganizationContactsList(
    query: String,
    contacts: List<GalContact>,
    isSearching: Boolean,
    error: String?,
    onContactClick: (GalContact) -> Unit
) {
    when {
        query.length < 2 -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Business,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        Strings.enterNameToSearch,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        isSearching -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        error != null -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
        contacts.isEmpty() -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(Strings.noContacts, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts) { contact ->
                    ContactItem(
                        name = contact.displayName,
                        email = contact.email,
                        company = contact.company,
                        onClick = { onContactClick(contact) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ContactItem(
    name: String,
    email: String,
    company: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                if (email.isNotBlank()) {
                    Text(email, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (company.isNotBlank()) {
                    Text(company, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(getAvatarColor(name)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.firstOrNull()?.uppercase() ?: "?",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}


@Composable
private fun ContactEditDialog(
    contact: ContactEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String, String, String, String, String, String) -> Unit
) {
    var displayName by rememberSaveable { mutableStateOf(contact?.displayName ?: "") }
    var email by rememberSaveable { mutableStateOf(contact?.email ?: "") }
    var firstName by rememberSaveable { mutableStateOf(contact?.firstName ?: "") }
    var lastName by rememberSaveable { mutableStateOf(contact?.lastName ?: "") }
    var phone by rememberSaveable { mutableStateOf(contact?.phone ?: "") }
    var mobilePhone by rememberSaveable { mutableStateOf(contact?.mobilePhone ?: "") }
    var workPhone by rememberSaveable { mutableStateOf(contact?.workPhone ?: "") }
    var company by rememberSaveable { mutableStateOf(contact?.company ?: "") }
    var department by rememberSaveable { mutableStateOf(contact?.department ?: "") }
    var jobTitle by rememberSaveable { mutableStateOf(contact?.jobTitle ?: "") }
    var notes by rememberSaveable { mutableStateOf(contact?.notes ?: "") }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (contact == null) Strings.addContact else Strings.editContact) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(Strings.displayName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text(Strings.firstName) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text(Strings.lastName) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(Strings.emailAddress) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(Strings.phone) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mobilePhone,
                    onValueChange = { mobilePhone = it },
                    label = { Text(Strings.mobilePhone) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = workPhone,
                    onValueChange = { workPhone = it },
                    label = { Text(Strings.workPhone) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = company,
                    onValueChange = { company = it },
                    label = { Text(Strings.company) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = department,
                    onValueChange = { department = it },
                    label = { Text(Strings.department) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = jobTitle,
                    onValueChange = { jobTitle = it },
                    label = { Text(Strings.jobTitle) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(Strings.contactNotes) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = displayName.ifBlank { "$firstName $lastName".trim().ifBlank { email } }
                    onSave(name, email, firstName, lastName, phone, mobilePhone, workPhone, company, department, jobTitle, notes)
                },
                enabled = displayName.isNotBlank() || email.isNotBlank() || firstName.isNotBlank() || lastName.isNotBlank()
            ) {
                Text(Strings.save)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        }
    )
}


@Composable
private fun ContactDetailsDialog(
    contact: Any, // ContactEntity или GalContact
    onDismiss: () -> Unit,
    onWriteEmail: (String) -> Unit,
    onCopyEmail: (String) -> Unit,
    onCall: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddToContacts: () -> Unit
) {
    val isLocal = contact is ContactEntity
    val name = when (contact) {
        is ContactEntity -> contact.displayName
        is GalContact -> contact.displayName
        else -> ""
    }
    val email = when (contact) {
        is ContactEntity -> contact.email
        is GalContact -> contact.email
        else -> ""
    }
    val phone = when (contact) {
        is ContactEntity -> contact.phone
        is GalContact -> contact.phone
        else -> ""
    }
    val mobilePhone = when (contact) {
        is ContactEntity -> contact.mobilePhone
        is GalContact -> contact.mobilePhone
        else -> ""
    }
    val company = when (contact) {
        is ContactEntity -> contact.company
        is GalContact -> contact.company
        else -> ""
    }
    val jobTitle = when (contact) {
        is ContactEntity -> contact.jobTitle
        is GalContact -> contact.jobTitle
        else -> ""
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(getAvatarColor(name)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.firstOrNull()?.uppercase() ?: "?",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(name)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (email.isNotBlank()) {
                    DetailRow(Icons.Default.Email, email) {
                        Row {
                            TextButton(onClick = { onWriteEmail(email) }) {
                                Text(Strings.writeEmail)
                            }
                            TextButton(onClick = { onCopyEmail(email) }) {
                                Text(Strings.copyEmail)
                            }
                        }
                    }
                }
                if (phone.isNotBlank()) {
                    DetailRow(Icons.Default.Phone, phone) {
                        TextButton(onClick = { onCall(phone) }) {
                            Text(Strings.callPhone)
                        }
                    }
                }
                if (mobilePhone.isNotBlank()) {
                    DetailRow(Icons.Default.PhoneAndroid, mobilePhone) {
                        TextButton(onClick = { onCall(mobilePhone) }) {
                            Text(Strings.callPhone)
                        }
                    }
                }
                if (company.isNotBlank()) {
                    DetailRow(Icons.Default.Business, company)
                }
                if (jobTitle.isNotBlank()) {
                    DetailRow(Icons.Default.Work, jobTitle)
                }
            }
        },
        confirmButton = {
            if (isLocal) {
                Row {
                    TextButton(onClick = onEdit) {
                        Text(Strings.edit)
                    }
                    TextButton(onClick = onDelete) {
                        Text(Strings.delete, color = MaterialTheme.colorScheme.error)
                    }
                }
            } else {
                TextButton(onClick = onAddToContacts) {
                    Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(Strings.addContact)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.close)
            }
        }
    )
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    actions: @Composable (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text)
            actions?.invoke()
        }
    }
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onExportVCard: () -> Unit,
    onExportCSV: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.exportContacts) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(Strings.exportToVCard) },
                    leadingContent = { Icon(Icons.Default.ContactPage, null) },
                    modifier = Modifier.clickable(onClick = onExportVCard)
                )
                ListItem(
                    headlineContent = { Text(Strings.exportToCSV) },
                    leadingContent = { Icon(Icons.Default.TableChart, null) },
                    modifier = Modifier.clickable(onClick = onExportCSV)
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel)
            }
        }
    )
}

private fun shareFile(context: android.content.Context, content: String, fileName: String, mimeType: String) {
    try {
        val file = java.io.File(context.cacheDir, fileName)
        file.writeText(content)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    } catch (e: Exception) {
        Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
    }
}
