/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.telecom.voip;

import android.telecom.CallException;

import com.android.server.telecom.LoggedHandlerExecutor;
import com.android.server.telecom.TelecomSystem;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A VoipCallTransaction implementation that its sub transactions will be executed in serial
 */
public class SerialTransaction extends VoipCallTransaction {
    public SerialTransaction(List<VoipCallTransaction> subTransactions,
            TelecomSystem.SyncRoot lock) {
        super(subTransactions, lock);
    }

    public void appendTransaction(VoipCallTransaction transaction){
        mSubTransactions.add(transaction);
    }

    @Override
    public void processTransactions() {
        if (mSubTransactions == null || mSubTransactions.isEmpty()) {
            scheduleTransaction();
            return;
        }
        TransactionManager.TransactionCompleteListener subTransactionListener =
                new TransactionManager.TransactionCompleteListener() {
                    private final AtomicInteger mTransactionIndex = new AtomicInteger(0);

                    @Override
                    public void onTransactionCompleted(VoipCallTransactionResult result,
                            String transactionName) {
                        if (result.getResult() != VoipCallTransactionResult.RESULT_SUCCEED) {
                            handleTransactionFailure();
                            CompletableFuture.completedFuture(null).thenApplyAsync(
                                    (x) -> {
                                        finish(result);
                                        mCompleteListener.onTransactionCompleted(result,
                                                mTransactionName);
                                        return null;
                                    }, new LoggedHandlerExecutor(mHandler,
                                            mTransactionName + "@" + hashCode()
                                                    + ".oTC", mLock));
                        } else {
                            int currTransactionIndex = mTransactionIndex.incrementAndGet();
                            if (currTransactionIndex < mSubTransactions.size()) {
                                VoipCallTransaction transaction = mSubTransactions.get(
                                        currTransactionIndex);
                                transaction.setCompleteListener(this);
                                transaction.start();
                            } else {
                                scheduleTransaction();
                            }
                        }
                    }

                    @Override
                    public void onTransactionTimeout(String transactionName) {
                        handleTransactionFailure();
                        CompletableFuture.completedFuture(null).thenApplyAsync(
                                (x) -> {
                                    VoipCallTransactionResult mainResult =
                                            new VoipCallTransactionResult(
                                                    CallException.CODE_OPERATION_TIMED_OUT,
                                            String.format("sub transaction %s timed out",
                                                    transactionName));
                                    finish(mainResult);
                                    mCompleteListener.onTransactionCompleted(mainResult,
                                            mTransactionName);
                                    return null;
                                }, new LoggedHandlerExecutor(mHandler,
                                        mTransactionName + "@" + hashCode()
                                                + ".oTT", mLock));
                    }
                };
        VoipCallTransaction transaction = mSubTransactions.get(0);
        transaction.setCompleteListener(subTransactionListener);
        transaction.start();

    }

    public void handleTransactionFailure() {}
}
