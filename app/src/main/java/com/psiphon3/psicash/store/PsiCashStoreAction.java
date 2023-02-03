/*
 * Copyright (c) 2021, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.psiphon3.psicash.store;

import androidx.annotation.Nullable;

import com.google.auto.value.AutoValue;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.mvibase.MviAction;

import io.reactivex.Flowable;

public interface PsiCashStoreAction extends MviAction {
    @AutoValue
    abstract class InitialAction implements PsiCashStoreAction {
        public static InitialAction create() {
            return new AutoValue_PsiCashStoreAction_InitialAction();
        }
    }

    @AutoValue
    abstract class GetPsiCash implements PsiCashStoreAction {
        public static GetPsiCash create(Flowable<TunnelState> tunnelStateFlowable) {
            return new AutoValue_PsiCashStoreAction_GetPsiCash(tunnelStateFlowable);
        }

        abstract Flowable<TunnelState> tunnelStateFlowable();
    }
    @AutoValue
    abstract class MakeExpiringPurchase implements PsiCashStoreAction {
        public static MakeExpiringPurchase create(@Nullable String distinguisher,
                                                  @Nullable String transactionClass,
                                                  long expectedPrice) {
            return new AutoValue_PsiCashStoreAction_MakeExpiringPurchase(distinguisher,
                    transactionClass, expectedPrice);
        }

        @Nullable
        public abstract String distinguisher();

        @Nullable
        public abstract String transactionClass();

        public abstract long expectedPrice();
    }
}
