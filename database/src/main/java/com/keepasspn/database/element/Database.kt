/*
 * Copyright 2019 Jeremy Jamet / Kunzisoft.
 *
 * This file is part of KeePassPN.
 *
 *  KeePassPN is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePassPN is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePassPN.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.keepasspn.database.element

import com.keepasspn.database.NoOpLog
import com.keepasspn.database.crypto.EncryptionAlgorithm
import com.keepasspn.database.crypto.kdf.KdfEngine
import com.keepasspn.database.crypto.kdf.Limits
import com.keepasspn.database.element.binary.AttachmentPool
import com.keepasspn.database.element.binary.BinaryCache
import com.keepasspn.database.element.binary.BinaryData
import com.keepasspn.database.element.database.CompressionAlgorithm
import com.keepasspn.database.element.database.DatabaseKDB
import com.keepasspn.database.element.database.DatabaseKDBX
import com.keepasspn.database.element.icon.IconImageCustom
import com.keepasspn.database.element.icon.IconImageStandard
import com.keepasspn.database.element.node.NodeHandler
import com.keepasspn.database.element.node.NodeIdInt
import com.keepasspn.database.element.node.NodeIdUUID
import com.keepasspn.database.element.template.Template
import com.keepasspn.database.element.template.TemplateEngine
import com.keepasspn.database.exception.DatabaseException
import com.keepasspn.database.exception.DatabaseInputException
import com.keepasspn.database.exception.DatabaseOutputException
import com.keepasspn.database.exception.FileNotFoundDatabaseException
import com.keepasspn.database.exception.MergeDatabaseKDBException
import com.keepasspn.database.exception.SignatureDatabaseException
import com.keepasspn.database.file.DatabaseHeaderKDB
import com.keepasspn.database.file.DatabaseHeaderKDBX
import com.keepasspn.database.file.DatabaseHeaderKDBX.Companion.FILE_VERSION_40
import com.keepasspn.database.file.input.DatabaseInputKDB
import com.keepasspn.database.file.input.DatabaseInputKDBX
import com.keepasspn.database.file.output.DatabaseOutputKDB
import com.keepasspn.database.file.output.DatabaseOutputKDBX
import com.keepasspn.database.merge.DatabaseKDBXMerger
import com.keepasspn.hardware.HardwareKey
import com.keepasspn.tasks.ProgressTaskUpdater
import com.keepasspn.utils.SingletonHolder
import com.keepasspn.utils.StringUtil.toFormattedColorInt
import com.keepasspn.utils.StringUtil.toFormattedColorString
import com.keepasspn.utils.readAllBytes
import com.keepasspn.utils.readBytes4ToUInt
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID


open class Database {

    // To keep a reference for specific methods provided by version
    private var mDatabaseKDB: DatabaseKDB? = null
    private var mDatabaseKDBX: DatabaseKDBX? = null

    var isReadOnly = false

    var allowUserVerification = false

    var loaded = false
        set(value) {
            field = value
            loadTimestamp = if (field) System.currentTimeMillis() else null
        }

    var loadTimestamp: Long? = null
        private set

    /**
     * Cipher key regenerated when the database is loaded and closed
     * Can be used to temporarily store database elements
     */
    var binaryCache: BinaryCache
        private set(value) {
            mDatabaseKDB?.binaryCache = value
            mDatabaseKDBX?.binaryCache = value
        }
        get() {
            return mDatabaseKDB?.binaryCache ?: mDatabaseKDBX?.binaryCache ?: BinaryCache()
        }

    private val icons = DatabaseIcons(
        kdb = { mDatabaseKDB },
        kdbx = { mDatabaseKDBX },
        binaryCache = { binaryCache }
    )

    fun doForEachStandardIcons(action: (IconImageStandard) -> Unit) {
        icons.doForEachStandardIcons(action)
    }

    fun getStandardIcon(iconId: Int): IconImageStandard {
        return icons.getStandardIcon(iconId)
    }

    val allowCustomIcons: Boolean
        get() = mDatabaseKDBX != null

    fun doForEachCustomIcons(action: (IconImageCustom, BinaryData) -> Unit) {
        icons.doForEachCustomIcons(action)
    }

    fun getCustomIcon(iconId: UUID): IconImageCustom? {
        return icons.getCustomIcon(iconId)
    }

    fun buildNewCustomIcon(result: (IconImageCustom?, BinaryData?) -> Unit) {
        icons.buildNewCustomIcon(result)
    }

    /**
     * Same as [buildNewCustomIcon], with the icon given a [name]
     */
    fun buildNewCustomIcon(name: String, result: (IconImageCustom?, BinaryData?) -> Unit) {
        icons.buildNewCustomIcon(name, result)
    }

    fun isCustomIconBinaryDuplicate(binaryData: BinaryData): Boolean {
        return icons.isCustomIconBinaryDuplicate(binaryData)
    }

    fun getBinaryForCustomIcon(iconId: UUID): BinaryData? {
        return icons.getBinaryForCustomIcon(iconId)
    }

    open fun removeCustomIcon(customIcon: IconImageCustom) {
        icons.removeCustomIcon(customIcon)
    }

    fun updateCustomIcon(customIcon: IconImageCustom) {
        icons.updateCustomIcon(customIcon)
    }

    fun getTemplates(templateCreation: Boolean): List<Template> {
        return mDatabaseKDBX?.getTemplates(templateCreation) ?: listOf()
    }

    fun getTemplate(entry: Entry): Template? {
        if (entryIsTemplate(entry))
            return TemplateEngine.CREATION
        entry.entryKDBX?.let { entryKDBX ->
            return mDatabaseKDBX?.getTemplate(entryKDBX)
        }
        return null
    }

    fun entryIsTemplate(entry: Entry?): Boolean {
        // Define if current entry is a template (in direct template group)
        if (entry == null || templatesGroup == null)
            return false
        // Retrieve the main historic entry to check if it's a template
        val mainEntry = if (entry.isHistoric()) {
            getEntryById(entry.getMainEntryHistoryId()) ?: entry
        } else entry
        return templatesGroup == mainEntry.parent
    }

    // Remove the template link in the entry data if it's a basic entry
    // or compress the template fields (as pseudo language) if it's a template entry
    protected fun decodeEntryWithTemplateConfiguration(
        entry: Entry
    ): Entry {
        entry.entryKDBX?.let {
            mDatabaseKDBX?.decodeEntryWithTemplateConfiguration(
                entryKDBX = it,
                entryIsTemplate = entryIsTemplate(entry)
            )?.let { decode ->
                return Entry(decode)
            }
        }
        return entry
    }

    protected fun encodeEntryWithTemplateConfiguration(
        entry: Entry,
        template: Template
    ): Entry {
        entry.entryKDBX?.let {
            mDatabaseKDBX?.encodeEntryWithTemplateConfiguration(
                entryKDBX = it,
                entryIsTemplate = entryIsTemplate(entry),
                template = template
            )?.let { encode ->
                return Entry(encode)
            }
        }
        return entry
    }

    val allowName: Boolean
        get() = mDatabaseKDBX != null

    var name: String
        get() {
            return mDatabaseKDBX?.name ?: ""
        }
        set(name) {
            mDatabaseKDBX?.name = name
            notifyNameChange()
        }

    val allowDescription: Boolean
        get() = mDatabaseKDBX != null

    var description: String
        get() {
            return mDatabaseKDBX?.description ?: ""
        }
        set(description) {
            mDatabaseKDBX?.description = description
            notifyDescriptionChange()
        }

    var defaultUsername: String
        get() {
            return mDatabaseKDB?.defaultUserName ?: mDatabaseKDBX?.defaultUserName ?: ""
        }
        set(username) {
            mDatabaseKDB?.defaultUserName = username
            mDatabaseKDBX?.defaultUserName = username
            notifyDefaultUserNameChange()
        }

    var customColor: Int?
        get() {
            var colorInt: Int? = null
            mDatabaseKDBX?.color?.let {
                try {
                    colorInt = it.toFormattedColorInt()
                } catch (_: Exception) {}
            }
            return mDatabaseKDB?.color ?: colorInt
        }
        set(value) {
            mDatabaseKDB?.color = value
            mDatabaseKDBX?.color = value?.toFormattedColorString() ?: ""
            notifySettingsChange()
        }

    val allowOTP: Boolean
        get() = mDatabaseKDBX != null

    val version: String
        get() = mDatabaseKDB?.version ?: mDatabaseKDBX?.version ?: "-"

    fun checkVersion() {
        mDatabaseKDBX?.getMinKdbxVersion()?.let {
            mDatabaseKDBX?.kdbxVersion = it
        }
    }

    val defaultFileExtension: String
        get() = mDatabaseKDB?.defaultFileExtension ?: mDatabaseKDBX?.defaultFileExtension ?: ".bin"

    val type: Class<*>?
        get() = mDatabaseKDB?.javaClass ?: mDatabaseKDBX?.javaClass

    val allowDataCompression: Boolean
        get() = mDatabaseKDBX != null

    val availableCompressionAlgorithms: List<CompressionAlgorithm>
        get() = mDatabaseKDBX?.availableCompressionAlgorithms ?: emptyList()

    var compressionAlgorithm: CompressionAlgorithm?
        get() = mDatabaseKDBX?.compressionAlgorithm
        set(value) {
            value?.let {
                mDatabaseKDBX?.compressionAlgorithm = it
            }
            notifySettingsChange()
        }

    fun compressionForNewEntry(): Boolean {
        if (mDatabaseKDB != null)
            return false
        // Default compression not necessary if stored in header
        mDatabaseKDBX?.let {
            return it.compressionAlgorithm == CompressionAlgorithm.GZIP
                    && it.kdbxVersion < FILE_VERSION_40
        }
        return false
    }

    fun updateDataBinaryCompression(
        oldCompression: CompressionAlgorithm,
        newCompression: CompressionAlgorithm
    ) {
        mDatabaseKDBX?.changeBinaryCompression(oldCompression, newCompression)
    }

    val allowNoMasterKey: Boolean
        get() = mDatabaseKDBX != null

    val passwordEncoding: Charset
        get() = mDatabaseKDB?.passwordEncoding
            ?: mDatabaseKDBX?.passwordEncoding
            ?: DEFAULT_PASSWORD_ENCODING

    fun getEncryptionAlgorithmName(): String {
        return mDatabaseKDB?.encryptionAlgorithm?.toString()
                ?: mDatabaseKDBX?.encryptionAlgorithm?.toString()
                ?: ""
    }

    val availableEncryptionAlgorithms: List<EncryptionAlgorithm>
        get() = mDatabaseKDB?.availableEncryptionAlgorithms ?: mDatabaseKDBX?.availableEncryptionAlgorithms ?: listOf()

    var encryptionAlgorithm: EncryptionAlgorithm?
        get() = mDatabaseKDB?.encryptionAlgorithm ?: mDatabaseKDBX?.encryptionAlgorithm
        set(algorithm) {
            algorithm?.let {
                mDatabaseKDBX?.encryptionAlgorithm = algorithm
                notifySettingsChange()
            }
        }

    val availableKdfEngines: List<KdfEngine>
        get() = mDatabaseKDB?.kdfAvailableList ?: mDatabaseKDBX?.kdfAvailableList ?: listOf()

    val allowKdfModification: Boolean
        get() = availableKdfEngines.size > 1

    var kdfEngine: KdfEngine?
        get() = mDatabaseKDB?.kdfEngine ?: mDatabaseKDBX?.kdfEngine
        set(kdfEngine) {
            mDatabaseKDB?.kdfEngine = kdfEngine
            mDatabaseKDBX?.kdfEngine = kdfEngine
            notifySettingsChange()
        }

    var masterKey: ByteArray
        get() = mDatabaseKDB?.masterKey ?: mDatabaseKDBX?.masterKey ?: ByteArray(32)
        set(masterKey) {
            mDatabaseKDB?.masterKey = masterKey
            mDatabaseKDBX?.masterKey = masterKey
            mDatabaseKDBX?.keyLastChanged = DateInstant()
            notifySettingsChange()
        }

    val transformSeed: ByteArray?
        get() = mDatabaseKDB?.transformSeed ?: mDatabaseKDBX?.transformSeed

    private val checkKey: ByteArray
        get() = mDatabaseKDB?.checkKey ?: mDatabaseKDBX?.checkKey ?: ByteArray(32)

    var rootGroup: Group?
        get() {
            mDatabaseKDB?.rootGroup?.let {
                return Group(it)
            }
            mDatabaseKDBX?.rootGroup?.let {
                return Group(it)
            }
            return null
        }
        set(value) {
            value?.groupKDB?.let { rootKDB ->
                mDatabaseKDB?.rootGroup = rootKDB
            }
            value?.groupKDBX?.let { rootKDBX ->
                mDatabaseKDBX?.rootGroup = rootKDBX
            }
        }

    val rootGroupIsVirtual: Boolean
        get() {
            mDatabaseKDB?.let {
                return true
            }
            mDatabaseKDBX?.let {
                return false
            }
            return true
        }

    val allowAddEntryInRoot: Boolean
        get() = mDatabaseKDBX != null

    val allowAddNoteInEachGroup: Boolean
        get() = mDatabaseKDBX != null

    /**
     * Do not modify groups here, used for read only
     */
    fun getAllGroupsWithoutRoot(): List<Group> {
        return mDatabaseKDB?.getAllGroupsWithoutRoot()?.map { Group(it) }
            ?: mDatabaseKDBX?.getAllGroupsWithoutRoot()?.map { Group(it) }
            ?: listOf()
    }

    val manageHistory: Boolean
        get() = mDatabaseKDBX != null

    var historyMaxItems: Int
        get() {
            return mDatabaseKDBX?.historyMaxItems ?: 0
        }
        set(value) {
            mDatabaseKDBX?.historyMaxItems = value
            notifySettingsChange()
        }

    var historyMaxSize: Long
        get() {
            return mDatabaseKDBX?.historyMaxSize ?: 0
        }
        set(value) {
            mDatabaseKDBX?.historyMaxSize = value
            notifySettingsChange()
        }

    fun notifyNameChange() {
        mDatabaseKDBX?.notifyNameChange()
    }

    fun notifyDescriptionChange() {
        mDatabaseKDBX?.notifyDescriptionChange()
    }

    fun notifyDefaultUserNameChange() {
        mDatabaseKDBX?.notifyDefaultUserNameChange()
    }

    fun notifySettingsChange() {
        mDatabaseKDBX?.notifySettingsChange()
    }

    /**
     * Determine if a configurable RecycleBin is available or not for this version of database
     * @return true if a configurable RecycleBin available
     */
    val allowConfigurableRecycleBin: Boolean
        get() = mDatabaseKDBX != null

    private val recycleBinSettings = DatabaseRecycleBin(
        kdb = { mDatabaseKDB },
        kdbx = { mDatabaseKDBX },
        ensureRecycleBinExists = { title -> ensureRecycleBinExists(title) },
        getGroupById = { id -> getGroupById(id) }
    )

    val isRecycleBinEnabled: Boolean
        get() = recycleBinSettings.isRecycleBinEnabled

    fun enableRecycleBin(enable: Boolean, recyclerBinTitle: String) {
        recycleBinSettings.enableRecycleBin(enable, recyclerBinTitle)
    }

    val recycleBin: Group?
        get() = recycleBinSettings.recycleBin

    fun setRecycleBin(group: Group?) {
        recycleBinSettings.setRecycleBin(group)
    }

    private val templatesSettings = DatabaseTemplates(
        kdbx = { mDatabaseKDBX }
    )

    /**
     * Determine if a configurable templates group is available or not for this version of database
     * @return true if a configurable templates group available
     */
    val allowTemplatesGroup: Boolean
        get() = templatesSettings.allowTemplatesGroup

    // Maybe another templates method with KDBX5
    val isTemplatesEnabled: Boolean
        get() = templatesSettings.isTemplatesEnabled

    fun enableTemplates(enable: Boolean, templatesGroupName: String) {
        templatesSettings.enableTemplates(enable, templatesGroupName)
    }

    val templatesGroup: Group?
        get() = templatesSettings.templatesGroup

    fun setTemplatesGroup(group: Group?) {
        templatesSettings.setTemplatesGroup(group)
    }

    val groupNamesNotAllowed: List<String>
        get() {
            return mDatabaseKDB?.groupNamesNotAllowed ?: listOf()
        }

    private fun setDatabaseKDB(databaseKDB: DatabaseKDB) {
        this.mDatabaseKDB = databaseKDB
        this.mDatabaseKDBX = null
    }

    private fun setDatabaseKDBX(databaseKDBX: DatabaseKDBX) {
        this.mDatabaseKDB = null
        this.mDatabaseKDBX = databaseKDBX
    }

    fun createData(
        databaseName: String,
        rootName: String,
        templateGroupName: String?
    ) {
        setDatabaseKDBX(DatabaseKDBX(databaseName, rootName, templateGroupName))
    }

    @Throws(DatabaseInputException::class)
    fun loadData(
        databaseStream: InputStream,
        masterCredential: MasterCredential,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
        readOnly: Boolean,
        allowUserVerification: Boolean,
        cacheDirectory: File,
        limits: Limits,
        fixDuplicateUUID: Boolean,
        progressTaskUpdater: ProgressTaskUpdater?
    ) {
        // Check if the file is writable
        this.isReadOnly = readOnly
        this.allowUserVerification = allowUserVerification

        try {
            kdfEngine?.checkLimits(limits)
            // Read database stream for the first time
            readDatabaseStream(databaseStream,
                    { databaseInputStream ->
                        val databaseKDB = DatabaseKDB().apply {
                            binaryCache.cacheDirectory = cacheDirectory
                            changeDuplicateId = fixDuplicateUUID
                        }
                        DatabaseInputKDB(databaseKDB)
                            .openDatabase(
                                databaseInputStream,
                                progressTaskUpdater
                            ) {
                                 databaseKDB.deriveMasterKey(
                                     masterCredential
                                 )
                            }
                        setDatabaseKDB(databaseKDB)
                    },
                    { databaseInputStream ->
                        val databaseKDBX = DatabaseKDBX().apply {
                            binaryCache.cacheDirectory = cacheDirectory
                            changeDuplicateId = fixDuplicateUUID
                        }
                        DatabaseInputKDBX(databaseKDBX).apply {
                            setMethodToCheckMemoryForBinary { memoryWanted ->
                                limits.isMemorySufficientForBinary(memoryWanted)
                            }
                            openDatabase(
                                databaseInputStream,
                                progressTaskUpdater
                            ) {
                                databaseKDBX.deriveMasterKey(
                                    masterCredential,
                                    challengeResponseRetriever
                                )
                            }
                        }
                        setDatabaseKDBX(databaseKDBX)
                    }
            )
            loaded = true
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to load the database")
            if (e is DatabaseInputException)
                throw e
            throw DatabaseInputException(e)
        }
    }

    /**
     * Check if the key is valid
     */
    fun checkKey(key: ByteArray): Boolean {
        return checkKey.contentEquals(key)
    }

    fun isMergeDataAllowed(): Boolean {
        return mDatabaseKDBX != null
    }

    @Throws(DatabaseInputException::class)
    fun mergeData(
        databaseToMergeStream: InputStream,
        databaseToMergeMasterCredential: MasterCredential?,
        databaseToMergeChallengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
        limits: Limits,
        progressTaskUpdater: ProgressTaskUpdater?
    ) {

        mDatabaseKDB?.let {
            throw MergeDatabaseKDBException()
        }

        kdfEngine?.checkLimits(limits)
        // New database instance to get new changes
        val databaseToMerge = Database()
        try {
            databaseToMerge.kdfEngine?.checkLimits(limits)
            readDatabaseStream(databaseToMergeStream,
                { databaseInputStream ->
                    val databaseToMergeKDB = DatabaseKDB()
                    DatabaseInputKDB(databaseToMergeKDB)
                        .openDatabase(databaseInputStream, progressTaskUpdater) {
                            if (databaseToMergeMasterCredential != null) {
                                databaseToMergeKDB.deriveMasterKey(
                                    databaseToMergeMasterCredential
                                )
                            } else {
                                this@Database.mDatabaseKDB?.let { thisDatabaseKDB ->
                                    databaseToMergeKDB.copyMasterKeyFrom(thisDatabaseKDB)
                                }
                            }
                        }
                    databaseToMerge.setDatabaseKDB(databaseToMergeKDB)
                },
                { databaseInputStream ->
                    val databaseToMergeKDBX = DatabaseKDBX()
                    DatabaseInputKDBX(databaseToMergeKDBX).apply {
                        setMethodToCheckMemoryForBinary { memoryWanted ->
                            limits.isMemorySufficientForBinary(memoryWanted)
                        }
                        openDatabase(databaseInputStream, progressTaskUpdater) {
                            if (databaseToMergeMasterCredential != null) {
                                databaseToMergeKDBX.deriveMasterKey(
                                    databaseToMergeMasterCredential,
                                    databaseToMergeChallengeResponseRetriever
                                )
                            } else {
                                this@Database.mDatabaseKDBX?.let { thisDatabaseKDBX ->
                                    databaseToMergeKDBX.copyMasterKeyFrom(thisDatabaseKDBX)
                                }
                            }
                        }
                    }
                    databaseToMerge.setDatabaseKDBX(databaseToMergeKDBX)
                }
            )
            loaded = true

            mDatabaseKDBX?.let { currentDatabaseKDBX ->
                val databaseMerger = DatabaseKDBXMerger(currentDatabaseKDBX).apply {
                    this.isRAMSufficient = { memoryWanted ->
                        limits.isMemorySufficientForBinary(memoryWanted)
                    }
                }
                databaseToMerge.mDatabaseKDB?.let { databaseKDBToMerge ->
                    databaseMerger.merge(databaseKDBToMerge)
                }
                databaseToMerge.mDatabaseKDBX?.let { databaseKDBXToMerge ->
                    databaseMerger.merge(databaseKDBXToMerge)
                }
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to merge the database")
            if (e is DatabaseException)
                throw e
            throw DatabaseInputException(e)
        } finally {
            databaseToMerge.clearAndClose()
        }
    }

    @Throws(DatabaseInputException::class)
    fun reloadData(
        databaseStream: InputStream,
        limits: Limits,
        progressTaskUpdater: ProgressTaskUpdater?
    ) {
        try {
            kdfEngine?.checkLimits(limits)
            // Retrieve the stream from the old database
            readDatabaseStream(databaseStream,
                { databaseInputStream ->
                    val databaseKDB = DatabaseKDB()
                    DatabaseInputKDB(databaseKDB)
                        .openDatabase(databaseInputStream, progressTaskUpdater) {
                            this@Database.mDatabaseKDB?.let { thisDatabaseKDB ->
                                databaseKDB.copyMasterKeyFrom(thisDatabaseKDB)
                            }
                        }
                    setDatabaseKDB(databaseKDB)
                },
                { databaseInputStream ->
                    val databaseKDBX = DatabaseKDBX()
                    DatabaseInputKDBX(databaseKDBX).apply {
                        setMethodToCheckMemoryForBinary { memoryWanted ->
                            limits.isMemorySufficientForBinary(memoryWanted)
                        }
                        openDatabase(databaseInputStream, progressTaskUpdater) {
                            this@Database.mDatabaseKDBX?.let { thisDatabaseKDBX ->
                                databaseKDBX.copyMasterKeyFrom(thisDatabaseKDBX)
                            }
                        }
                    }
                    setDatabaseKDBX(databaseKDBX)
                }
            )
            loaded = true
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to reload the database")
            if (e is DatabaseException)
                throw e
            throw DatabaseInputException(e)
        }
    }

    @Throws(Exception::class)
    private fun readDatabaseStream(
        databaseStream: InputStream,
        openDatabaseKDB: (InputStream) -> Unit,
        openDatabaseKDBX: (InputStream) -> Unit
    ) {
        try {
            // Load Data by InputStream
            BufferedInputStream(databaseStream).use { databaseInputStream ->

                // We'll end up reading 8 bytes to identify the header. Might as well use two extra.
                databaseInputStream.mark(10)

                // Get the file directory to save the attachments
                val sig1 = databaseInputStream.readBytes4ToUInt()
                val sig2 = databaseInputStream.readBytes4ToUInt()

                // Return to the start
                databaseInputStream.reset()

                when {
                    // Header of database KDB
                    DatabaseHeaderKDB.matchesHeader(sig1, sig2) -> openDatabaseKDB(
                        databaseInputStream
                    )
                    // Header of database KDBX
                    DatabaseHeaderKDBX.matchesHeader(sig1, sig2) -> openDatabaseKDBX(
                        databaseInputStream
                    )
                    // Header not recognized
                    else -> throw SignatureDatabaseException()
                }
            }
        } catch (_ : FileNotFoundException) {
            throw FileNotFoundDatabaseException()
        }
    }

    @Throws(DatabaseOutputException::class)
    fun saveData(
        cacheFile: File,
        databaseOutputStream: () -> OutputStream?,
        databaseInputStream: () -> InputStream?,
        masterCredential: MasterCredential?,
        challengeResponseRetriever: (HardwareKey, ByteArray?) -> ByteArray,
        limits: Limits
    ) {
        var cacheComplete = false
        try {
            kdfEngine?.checkLimits(limits)
            // Save in a temp memory to avoid exception
            cacheFile.outputStream().use { outputStream ->
                mDatabaseKDB?.let { databaseKDB ->
                    DatabaseOutputKDB(databaseKDB).apply {
                        writeDatabase(outputStream) {
                            if (masterCredential != null) {
                                databaseKDB.deriveMasterKey(
                                    masterCredential
                                )
                            } else {
                                // No master key change
                            }
                        }
                    }
                }
                ?: mDatabaseKDBX?.let { databaseKDBX ->
                    DatabaseOutputKDBX(databaseKDBX).apply {
                        writeDatabase(outputStream) {
                            if (masterCredential != null) {
                                // Build new master key from MainCredential
                                databaseKDBX.deriveMasterKey(
                                    masterCredential,
                                    challengeResponseRetriever
                                )
                            } else {
                                // Reuse composite key parts
                                databaseKDBX.deriveCompositeKey(
                                    challengeResponseRetriever
                                )
                            }
                        }
                    }
                }
            }
            cacheComplete = true
            val expectedHash = cacheFile.sha256()
            // Copy from the cache to the destination
            val output = databaseOutputStream.invoke()
                ?: throw DatabaseOutputException(IOException("Unable to open the database destination"))
            output.use { outputStream ->
                cacheFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
                outputStream.flush()
            }
            // Read the destination back when the provider allows it. A readable copy that
            // differs from the cache means the save failed; an unreadable one keeps the previous behavior.
            val savedHash = try {
                databaseInputStream.invoke()?.sha256()
            } catch (e: Exception) {
                NoOpLog.w(TAG, "Unable to read back the saved database", e)
                null
            }
            if (savedHash != null && savedHash != expectedHash)
                throw DatabaseOutputException(IOException("Saved database does not match the cache"))
            // The cache is only needed until the save is verified
            cacheFile.delete()
        } catch (e: Exception) {
            // A partial cache is not valid, drop it. A complete cache is kept for recovery.
            if (!cacheComplete) cacheFile.delete()
            NoOpLog.e(TAG, "Unable to save database", e)
            if (e is DatabaseException)
                throw e
            throw DatabaseOutputException(e)
        }
    }

    fun groupIsInRecycleBin(group: Group): Boolean {
        val groupKDB = group.groupKDB
        val groupKDBX = group.groupKDBX
        if (groupKDB != null) {
            return mDatabaseKDB?.isInRecycleBin(groupKDB) ?: false
        } else if (groupKDBX != null) {
            return mDatabaseKDBX?.isInRecycleBin(groupKDBX) ?: false
        }
        return false
    }

    fun groupIsInTemplates(group: Group): Boolean {
        val groupKDBX = group.groupKDBX
        if (groupKDBX != null) {
            return mDatabaseKDBX?.getTemplatesGroup() == groupKDBX
        }
        return false
    }

    val tagPool: Tags
        get() = mDatabaseKDBX?.tagPool ?: Tags()

    val tagPoolWithoutHistory: Tags
        get() = mDatabaseKDBX?.getTagPoolWithoutHistory() ?: Tags()

    private val attachments = DatabaseAttachments(
        kdb = { mDatabaseKDB },
        kdbx = { mDatabaseKDBX },
        compressionForNewEntry = { compressionForNewEntry() }
    )

    val attachmentPool: AttachmentPool
        get() = attachments.attachmentPool

    val allowMultipleAttachments: Boolean
        get() = attachments.allowMultipleAttachments

    fun buildNewBinaryAttachment(): BinaryData? {
        return attachments.buildNewBinaryAttachment()
    }

    /**
     * Remove the [binaryData] from the binary pool if not used
     * @param clearCache Clear the cache if true
     */
    fun removeBinaryIfNotUsed(binaryData: BinaryData, clearCache: Boolean = false) {
        attachments.removeBinaryIfNotUsed(binaryData, clearCache)
    }

    /**
     * Remove the binary data of an [Attachment] from the binary pool if not used
     * @param clearCache Clear the cache if true
     */
    fun removeAttachmentIfNotUsed(attachment: Attachment, clearCache: Boolean = false) {
        attachments.removeAttachmentIfNotUsed(attachment, clearCache)
    }

    fun removeUnlinkedAttachments() {
        attachments.removeUnlinkedAttachments()
    }

    open fun clearIndexesAndBinaries(filesDirectory: File?) {
        this.mDatabaseKDB?.clearIndexes()
        this.mDatabaseKDBX?.clearIndexes()

        this.mDatabaseKDB?.clearIconsCache()
        this.mDatabaseKDBX?.clearIconsCache()

        this.mDatabaseKDB?.clearAttachmentsCache()
        this.mDatabaseKDBX?.clearAttachmentsCache()

        this.mDatabaseKDB?.clearBinaries()
        this.mDatabaseKDBX?.clearBinaries()

        // delete all the files in the temp dir if allowed
        try {
            filesDirectory?.let { directory ->
                cleanDirectory(directory)
            }
        } catch (e: Exception) {
            NoOpLog.e(TAG, "Unable to clear the directory cache.", e)
        }
    }

    open fun clearAndClose(filesDirectory: File? = null) {
        clearIndexesAndBinaries(filesDirectory)
        this.mDatabaseKDB?.clearSensitiveData()
        this.mDatabaseKDBX?.clearSensitiveData()
        this.mDatabaseKDB = null
        this.mDatabaseKDBX = null
        this.loaded = false
    }

    private fun cleanDirectory(directory: File) {
        directory.listFiles()?.let { files ->
            for (file in files) {
                if (file.isDirectory) {
                    cleanDirectory(file)
                }
                file.delete()
            }
        }
    }

    fun isValidCredential(masterCredential: MasterCredential): Boolean {
        val password = masterCredential.password
        val containsKeyFile = masterCredential.keyFileData != null
        return mDatabaseKDB?.isValidCredential(password, containsKeyFile)
                ?: mDatabaseKDBX?.isValidCredential(password, containsKeyFile)
                ?: false
    }

    fun rootCanContainsEntry(): Boolean {
        return mDatabaseKDB?.rootCanContainsEntry() ?: mDatabaseKDBX?.rootCanContainsEntry() ?: false
    }

    fun createEntry(): Entry? {
        mDatabaseKDB?.let { database ->
            return Entry(database.createEntry()).apply {
                nodeId = database.newEntryId()
            }
        }
        mDatabaseKDBX?.let { database ->
            return Entry(database.createEntry()).apply {
                nodeId = database.newEntryId()
            }
        }

        return null
    }

    fun createGroup(): Group? {
        var group: Group? = null
        mDatabaseKDB?.let { database ->
            group = Group(database.createGroup()).apply {
                setNodeId(database.newGroupId())
            }
        }
        mDatabaseKDBX?.let { database ->
            group = Group(database.createGroup()).apply {
                setNodeId(database.newGroupId())
            }
        }

        return group
    }

    fun getEntryById(id: EntryId): Entry? {
        mDatabaseKDB?.getEntryById(id)?.let {
            return Entry(it)
        }
        mDatabaseKDBX?.getEntryById(id)?.let {
            return Entry(it)
        }
        return null
    }

    fun getEntriesByIds(entriesIds: List<EntryId>): List<Entry> {
        return entriesIds.mapNotNull { getEntryById(it) }
    }

    fun getGroupById(groupId: GroupId): Group? {
        when (groupId) {
            is NodeIdInt -> {
                mDatabaseKDB?.getGroupById(groupId)?.let {
                    return Group(it)
                }
            }
            is NodeIdUUID -> {
                mDatabaseKDBX?.getGroupById(groupId)?.let {
                    return Group(it)
                }
            }
        }
        return null
    }

    fun getGroupsByIds(groupsIds: List<GroupId>): List<Group> {
        return groupsIds.mapNotNull { getGroupById(it) }
    }

    fun addEntryTo(entry: Entry, parent: Group) {
        entry.entryKDB?.let { entryKDB ->
            mDatabaseKDB?.addEntryTo(entryKDB, parent.groupKDB)
        }
        entry.entryKDBX?.let { entryKDBX ->
            mDatabaseKDBX?.addEntryTo(entryKDBX, parent.groupKDBX)
        }
        entry.afterAssignNewParent()
    }

    fun updateEntry(entry: Entry) {
        entry.entryKDB?.let { entryKDB ->
            mDatabaseKDB?.updateEntry(entryKDB)
        }
        entry.entryKDBX?.let { entryKDBX ->
            mDatabaseKDBX?.updateEntry(entryKDBX)
        }
    }

    fun removeEntryFrom(entry: Entry, parent: Group) {
        entry.entryKDB?.let { entryKDB ->
            mDatabaseKDB?.removeEntryFrom(entryKDB, parent.groupKDB)
        }
        entry.entryKDBX?.let { entryKDBX ->
            mDatabaseKDBX?.removeEntryFrom(entryKDBX, parent.groupKDBX)
        }
        entry.afterAssignNewParent()
    }

    fun addGroupTo(group: Group, parent: Group) {
        group.groupKDB?.let { groupKDB ->
            mDatabaseKDB?.addGroupTo(groupKDB, parent.groupKDB)
        }
        group.groupKDBX?.let { groupKDBX ->
            mDatabaseKDBX?.addGroupTo(groupKDBX, parent.groupKDBX)
        }
        group.afterAssignNewParent()
    }

    fun updateGroup(group: Group) {
        group.groupKDB?.let { entryKDB ->
            mDatabaseKDB?.updateGroup(entryKDB)
        }
        group.groupKDBX?.let { entryKDBX ->
            mDatabaseKDBX?.updateGroup(entryKDBX)
        }
    }

    fun removeGroupFrom(group: Group, parent: Group) {
        group.groupKDB?.let { groupKDB ->
            mDatabaseKDB?.removeGroupFrom(groupKDB, parent.groupKDB)
        }
        group.groupKDBX?.let { groupKDBX ->
            mDatabaseKDBX?.removeGroupFrom(groupKDBX, parent.groupKDBX)
        }
        group.afterAssignNewParent()
    }

    /**
     * @return A duplicate entry with the same values, a new random UUID and a new parent
     * @param entryToCopy
     * @param newParent
     */
    fun copyEntryTo(entryToCopy: Entry, newParent: Group): Entry {
        val entryCopied = Entry(entryToCopy, copyHistory = false)
        entryCopied.nodeId = mDatabaseKDB?.newEntryId() ?: mDatabaseKDBX?.newEntryId() ?: NodeIdUUID()
        entryCopied.parent = newParent
        entryCopied.title += " (~)"
        addEntryTo(entryCopied, newParent)
        return entryCopied
    }

    /**
     * @return A duplicate group with the same values, a new random UUID and a new parent
     * @param groupToCopy
     * @param newParent
     */
    fun copyGroupTo(groupToCopy: Group, newParent: Group): Group {
        val groupCopied = Group(groupToCopy)
        val newId = mDatabaseKDB?.newGroupId() ?: mDatabaseKDBX?.newGroupId() ?: NodeIdUUID()
        when (newId) {
            is NodeIdInt -> groupCopied.setNodeId(newId)
            is NodeIdUUID -> groupCopied.setNodeId(newId)
        }
        groupCopied.parent = newParent
        groupCopied.title += " (~)"
        addGroupTo(groupCopied, newParent)

        // Copy entries
        groupToCopy.getChildEntries().forEach {
            copyEntryTo(it, groupCopied)
        }

        // Copy sub-groups
        groupToCopy.getChildGroups().forEach {
            copyGroupTo(it, groupCopied)
        }

        return groupCopied
    }

    fun moveEntryTo(entryToMove: Entry, newParent: Group) {
        entryToMove.parent?.let {
            removeEntryFrom(entryToMove, it)
        }
        addEntryTo(entryToMove, newParent)
    }

    fun moveGroupTo(groupToMove: Group, newParent: Group) {
        groupToMove.parent?.let {
            removeGroupFrom(groupToMove, it)
        }
        addGroupTo(groupToMove, newParent)
    }

    fun deleteEntry(entry: Entry) {
        entry.entryKDBX?.id?.let { entryId ->
            mDatabaseKDBX?.addDeletedObject(entryId)
        }
        entry.parent?.let {
            removeEntryFrom(entry, it)
        }
    }

    fun deleteGroup(group: Group) {
        group.doForEachChildAndForIt(
                object : NodeHandler<Entry>() {
                    override fun operate(node: Entry): Boolean {
                        deleteEntry(node)
                        return true
                    }
                },
                object : NodeHandler<Group>() {
                    override fun operate(node: Group): Boolean {
                        node.groupKDBX?.id?.let { groupId ->
                            mDatabaseKDBX?.addDeletedObject(groupId)
                        }
                        node.parent?.let {
                            removeGroupFrom(node, it)
                        }
                        return true
                    }
                })
    }

    fun ensureRecycleBinExists(recyclerBinTitle: String) {
        mDatabaseKDB?.ensureBackupExists()
        mDatabaseKDBX?.ensureRecycleBinExists(recyclerBinTitle)
    }

    fun canRecycle(entry: Entry): Boolean {
        var canRecycle: Boolean? = null
        entry.entryKDB?.let {
            canRecycle = mDatabaseKDB?.canRecycle(it)
        }
        entry.entryKDBX?.let {
            canRecycle = mDatabaseKDBX?.canRecycle(it)
        }
        return canRecycle ?: false
    }

    fun canRecycle(group: Group): Boolean {
        var canRecycle: Boolean? = null
        group.groupKDB?.let {
            canRecycle = mDatabaseKDB?.canRecycle(it)
        }
        group.groupKDBX?.let {
            canRecycle = mDatabaseKDBX?.canRecycle(it)
        }
        return canRecycle ?: false
    }

    fun recycle(entry: Entry, recyclerBinTitle: String) {
        ensureRecycleBinExists(recyclerBinTitle)
        entry.parent?.let { parent ->
            removeEntryFrom(entry, parent)
        }
        recycleBin?.let {
            addEntryTo(entry, it)
        }
        entry.afterAssignNewParent()
    }

    fun recycle(group: Group, recyclerBinTitle: String) {
        ensureRecycleBinExists(recyclerBinTitle)
        group.parent?.let { parent ->
            removeGroupFrom(group, parent)
        }
        recycleBin?.let {
            addGroupTo(group, it)
        }
        group.afterAssignNewParent()
    }

    fun undoRecycle(entry: Entry, parent: Group) {
        recycleBin?.let {
            removeEntryFrom(entry, it)
        }
        addEntryTo(entry, parent)
        entry.afterAssignNewParent()
    }

    fun undoRecycle(group: Group, parent: Group) {
        recycleBin?.let {
            removeGroupFrom(group, it)
        }
        addGroupTo(group, parent)
        group.afterAssignNewParent()
    }

    fun startManageEntry(entry: Entry?) {
        mDatabaseKDBX?.let {
            entry?.startToManageFieldReferences(it)
        }
    }

    fun stopManageEntry(entry: Entry?) {
        mDatabaseKDBX?.let {
            entry?.stopToManageFieldReferences()
        }
    }

    /**
     * @return true if database allows custom field
     */
    fun allowEntryCustomFields(): Boolean {
        return mDatabaseKDBX != null
    }

    fun allowCustomSearchableGroup(): Boolean {
        return mDatabaseKDBX != null
    }

    fun allowAutoType(): Boolean {
        return mDatabaseKDBX != null
    }

    fun allowTags(): Boolean {
        return mDatabaseKDBX != null
    }

    /**
     * Remove oldest history for each entry if more than max items or max memory
     */
    fun removeOldestHistoryForEachEntry() {
        rootGroup?.doForEachChildAndForIt(
                object : NodeHandler<Entry>() {
                    override fun operate(node: Entry): Boolean {
                        removeOldestEntryHistory(node, attachmentPool)
                        return true
                    }
                },
                object : NodeHandler<Group>() {
                    override fun operate(node: Group): Boolean {
                        return true
                    }
                }
        )
    }

    /**
     * Remove oldest history if more than max items or max memory
     */
    fun removeOldestEntryHistory(entry: Entry, attachmentPool: AttachmentPool) {
        mDatabaseKDBX?.let {
            val maxItems = historyMaxItems
            if (maxItems >= 0) {
                while (entry.getHistory().size > maxItems) {
                    removeOldestEntryHistory(entry)
                }
            }

            val maxSize = historyMaxSize
            if (maxSize >= 0) {
                while (true) {
                    var historySize: Long = 0
                    for (entryHistory in entry.getHistory()) {
                        historySize += entryHistory.getSize(attachmentPool)
                    }
                    if (historySize > maxSize) {
                        removeOldestEntryHistory(entry)
                    } else {
                        break
                    }
                }
            }
        }
    }

    private fun removeOldestEntryHistory(entry: Entry) {
        entry.removeOldestEntryFromHistory()?.let {
            it.getAttachments(attachmentPool, false).forEach { attachmentToRemove ->
                removeAttachmentIfNotUsed(attachmentToRemove)
            }
        }
    }

    fun removeEntryHistory(entry: Entry, entryHistoryPosition: Int) {
        entry.removeEntryFromHistory(entryHistoryPosition)?.let {
            it.getAttachments(attachmentPool, false).forEach { attachmentToRemove ->
                removeAttachmentIfNotUsed(attachmentToRemove)
            }
        }
    }

    companion object : SingletonHolder<Database>(::Database) {
        private val TAG = Database::class.java.name

        val DEFAULT_PASSWORD_ENCODING: Charset = StandardCharsets.UTF_8
    }
}

private fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8192)
    use { stream ->
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { String.format("%02x", it) }
}

private fun File.sha256(): String = inputStream().use { it.sha256() }
