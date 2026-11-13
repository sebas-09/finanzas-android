package ec.finanzas.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object FinancialMigrations {
    val FROM_4_TO_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE budget ADD COLUMN alertsEnabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE budget ADD COLUMN threshold INTEGER NOT NULL DEFAULT 80")
            db.execSQL("ALTER TABLE budget ADD COLUMN lastNotifiedLevel INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE candidate ADD COLUMN categoryId TEXT")
            db.execSQL("ALTER TABLE candidate ADD COLUMN categoryDecided INTEGER NOT NULL DEFAULT 0")
        }
    }
    val FROM_3_TO_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE ledger_transaction ADD COLUMN counterAccountId TEXT")
            db.execSQL("ALTER TABLE ledger_transaction ADD COLUMN note TEXT")
            db.execSQL("ALTER TABLE ledger_transaction ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE ledger_transaction ADD COLUMN estimatedDate INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE ledger_transaction ADD COLUMN mergedIntoId TEXT")
            db.execSQL("CREATE TABLE IF NOT EXISTS ledger_version (id TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO ledger_version(id,version) SELECT id,revision FROM ledger_transaction")
            db.execSQL("ALTER TABLE candidate ADD COLUMN counterAccountId TEXT")
            db.execSQL("ALTER TABLE candidate ADD COLUMN originalTransactionId TEXT")
            db.execSQL("ALTER TABLE candidate ADD COLUMN note TEXT")
            db.execSQL("ALTER TABLE candidate ADD COLUMN estimatedDate INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_source_observation_sourcePackage_keyHash_postedAt ON source_observation(sourcePackage,keyHash,postedAt)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ledger_history (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ownerId TEXT NOT NULL, beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL, createdAt INTEGER NOT NULL, undoneAt INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_history_ownerId ON ledger_history(ownerId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_candidate_status_receivedAt ON candidate(status,receivedAt)")
            db.execSQL("INSERT OR IGNORE INTO card_profile(id,accountId,type,issuer) SELECT 'legacy-debit:' || id,id,'DEBIT',provider FROM financial_account WHERE kind='DEBIT_CARD'")
            db.execSQL("UPDATE financial_account SET kind='BANK_ACCOUNT' WHERE kind='DEBIT_CARD'")
        }
    }

    val FROM_2_TO_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `candidate` (`id` TEXT NOT NULL, `amountMinor` INTEGER, `occurredAt` INTEGER, `direction` TEXT, `provider` TEXT, `instrument` TEXT, `merchant` TEXT, `candidateKind` TEXT NOT NULL, `parserVersion` TEXT NOT NULL, `confidence` REAL NOT NULL, `evidenceCodes` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, `origin` TEXT NOT NULL, `status` TEXT NOT NULL, `accountId` TEXT, `classification` TEXT, PRIMARY KEY(`id`))")
            db.execSQL("INSERT INTO candidate(id,amountMinor,occurredAt,direction,provider,merchant,candidateKind,parserVersion,confidence,evidenceCodes,receivedAt,origin,status) SELECT id,amountMinor,occurredAt,direction,provider,merchantNormalized,'UNKNOWN',parserVersion,confidence,evidenceCodes,receivedAt,origin,'NEEDS_REVIEW' FROM source_observation")
        }
    }

    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `notification_cursor` (`keyHash` TEXT NOT NULL, `eventFingerprint` TEXT NOT NULL, `sessionId` INTEGER NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`keyHash`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `financial_account` (`id` TEXT NOT NULL, `alias` TEXT NOT NULL, `provider` TEXT NOT NULL, `kind` TEXT NOT NULL, `openingBalanceMinor` INTEGER, `currency` TEXT NOT NULL, `active` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `card_profile` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `type` TEXT NOT NULL, `issuer` TEXT NOT NULL, `billingDay` INTEGER, `dueDay` INTEGER, `creditLimitMinor` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `financial_account`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_profile_accountId` ON `card_profile` (`accountId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `category` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, `icon` TEXT NOT NULL, `active` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`parentId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_category_parentId` ON `category` (`parentId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `ledger_transaction` (`id` TEXT NOT NULL, `accountId` TEXT, `occurredAt` INTEGER NOT NULL, `observedAt` INTEGER NOT NULL, `amountMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `direction` TEXT NOT NULL, `kind` TEXT NOT NULL, `categoryId` TEXT, `merchantAlias` TEXT, `status` TEXT NOT NULL, `origin` TEXT NOT NULL, `originalTransactionId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`accountId`) REFERENCES `financial_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, FOREIGN KEY(`originalTransactionId`) REFERENCES `ledger_transaction`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_occurredAt` ON `ledger_transaction` (`occurredAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_accountId_occurredAt` ON `ledger_transaction` (`accountId`, `occurredAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_origin_status` ON `ledger_transaction` (`origin`, `status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_categoryId` ON `ledger_transaction` (`categoryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_ledger_transaction_originalTransactionId` ON `ledger_transaction` (`originalTransactionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `account_posting` (`id` TEXT NOT NULL, `transactionId` TEXT NOT NULL, `accountId` TEXT NOT NULL, `deltaMinor` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`transactionId`) REFERENCES `ledger_transaction`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`accountId`) REFERENCES `financial_account`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_account_posting_transactionId_accountId` ON `account_posting` (`transactionId`, `accountId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_account_posting_accountId` ON `account_posting` (`accountId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `source_observation` (`id` TEXT NOT NULL, `transactionId` TEXT, `sourcePackage` TEXT NOT NULL, `eventFingerprint` TEXT NOT NULL, `keyHash` TEXT NOT NULL, `revisionHash` TEXT NOT NULL, `parserVersion` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `lastReceivedAt` INTEGER NOT NULL, `provider` TEXT, `origin` TEXT NOT NULL, `state` TEXT NOT NULL, `confidence` REAL NOT NULL, `evidenceCodes` TEXT NOT NULL, `amountMinor` INTEGER, `occurredAt` INTEGER, `currency` TEXT NOT NULL, `direction` TEXT, `merchantNormalized` TEXT, `transactionReferenceHash` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`transactionId`) REFERENCES `ledger_transaction`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_source_observation_sourcePackage_eventFingerprint_origin` ON `source_observation` (`sourcePackage`, `eventFingerprint`, `origin`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_observation_receivedAt` ON `source_observation` (`receivedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_observation_origin_state` ON `source_observation` (`origin`, `state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_observation_transactionId` ON `source_observation` (`transactionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `transfer_link` (`id` TEXT NOT NULL, `debitTransactionId` TEXT NOT NULL, `creditTransactionId` TEXT NOT NULL, `linkType` TEXT NOT NULL, `confirmationState` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`debitTransactionId`) REFERENCES `ledger_transaction`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(`creditTransactionId`) REFERENCES `ledger_transaction`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_transfer_link_debitTransactionId_creditTransactionId` ON `transfer_link` (`debitTransactionId`, `creditTransactionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_transfer_link_creditTransactionId` ON `transfer_link` (`creditTransactionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `merchant_rule` (`id` TEXT NOT NULL, `matchType` TEXT NOT NULL, `pattern` TEXT NOT NULL, `merchantAlias` TEXT NOT NULL, `categoryId` TEXT, `priority` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_merchant_rule_categoryId` ON `merchant_rule` (`categoryId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `review_queue` (`id` TEXT NOT NULL, `observationId` TEXT NOT NULL, `reasonCode` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `resolvedAt` INTEGER, `resolution` TEXT, PRIMARY KEY(`id`), FOREIGN KEY(`observationId`) REFERENCES `source_observation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_review_queue_observationId_reasonCode` ON `review_queue` (`observationId`, `reasonCode`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `capture_session` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `endReason` TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_session_startedAt` ON `capture_session` (`startedAt`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `capture_settings` (`id` INTEGER NOT NULL, `consentAt` INTEGER, `consentVersion` INTEGER NOT NULL, `onboardingCompleted` INTEGER NOT NULL, `captureStartedAt` INTEGER, `requestedActive` INTEGER NOT NULL, `pausedAt` INTEGER, `permissionGranted` INTEGER NOT NULL, `permissionEverGranted` INTEGER NOT NULL, `listenerConnected` INTEGER NOT NULL, `currentSessionId` INTEGER, `mayHaveGaps` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`currentSessionId`) REFERENCES `capture_session`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_settings_currentSessionId` ON `capture_settings` (`currentSessionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `allowed_source` (`packageName` TEXT NOT NULL, `displayName` TEXT NOT NULL, `provider` TEXT, `channel` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `enabledSince` INTEGER, `signingDigest` TEXT NOT NULL, `available` INTEGER NOT NULL, `lastSeenAt` INTEGER, PRIMARY KEY(`packageName`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `capture_queue` (`eventFingerprint` TEXT NOT NULL, `sourcePackage` TEXT NOT NULL, `keyHash` TEXT NOT NULL, `revisionHash` TEXT NOT NULL, `sessionId` INTEGER, `postedAt` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `lastReceivedAt` INTEGER NOT NULL, `provider` TEXT, `origin` TEXT NOT NULL, PRIMARY KEY(`eventFingerprint`), FOREIGN KEY(`sessionId`) REFERENCES `capture_session`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_queue_receivedAt` ON `capture_queue` (`receivedAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_capture_queue_sessionId` ON `capture_queue` (`sessionId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `diagnostic_counter` (`reason` TEXT NOT NULL, `count` INTEGER NOT NULL, PRIMARY KEY(`reason`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `change_audit` (`id` TEXT NOT NULL, `entityId` TEXT NOT NULL, `changeKind` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `fieldsChanged` TEXT NOT NULL, PRIMARY KEY(`id`))")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_change_audit_timestamp` ON `change_audit` (`timestamp`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `budget` (`id` TEXT NOT NULL, `categoryId` TEXT NOT NULL, `month` TEXT NOT NULL, `limitMinor` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_categoryId_month` ON `budget` (`categoryId`, `month`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `card_cycle` (`id` TEXT NOT NULL, `cardId` TEXT NOT NULL, `startsAt` INTEGER NOT NULL, `endsAt` INTEGER NOT NULL, `dueAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`cardId`) REFERENCES `card_profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_card_cycle_cardId_startsAt` ON `card_cycle` (`cardId`, `startsAt`)")
        }
    }
}
