package com.baylee.billnest.data

import com.baylee.billnest.model.Account
import com.baylee.billnest.model.AccountSource
import com.baylee.billnest.model.Bill
import com.baylee.billnest.model.Payday
import com.baylee.billnest.model.SyncMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMappingTest {
    @Test
    fun billRoundTripsThroughSharedRecordPayload() {
        val bill = Bill(name = "Electric", amount = 121.44, dueDateIso = "2026-09-20")
        val encoded = SyncMapper.encodeBill(bill)
        val decoded = SyncMapper.decodeBill(encoded)
        assertEquals(bill, decoded)
    }

    @Test
    fun paydayRoundTripsThroughSharedRecordPayload() {
        val payday = Payday(label = "Paycheck", amount = 1800.0, nextDateIso = "2026-09-18")
        assertEquals(payday, SyncMapper.decodePayday(SyncMapper.encodePayday(payday)))
    }

    @Test
    fun plaidAccountsAreNotUploadedAsManualAccountRecords() {
        val plaid = Account(name = "Checking", source = AccountSource.PLAID, plaidAccountId = "p1")
        assertNull(SyncMapper.accountMutation(plaid))
    }

    @Test
    fun manualAccountsProduceSharedRecordDrafts() {
        val manual = Account(name = "Cash", source = AccountSource.MANUAL, balance = 75.0)
        val draft = SyncMapper.accountMutation(manual)
        assertEquals("manual_account", draft?.kind)
        assertEquals(manual.id, draft?.recordId)
        assertEquals(manual, SyncMapper.decodeAccount(draft!!.payloadJson))
    }
}
