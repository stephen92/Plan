/*
 *  This file is part of Player Analytics (Plan).
 *
 *  Plan is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License v3 as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Plan is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with Plan. If not, see <https://www.gnu.org/licenses/>.
 */
package net.playeranalytics.plan;

import com.djrapitops.plan.gathering.cache.SessionCache;
import com.djrapitops.plan.gathering.domain.ActiveSession;
import com.djrapitops.plan.gathering.domain.PlayerKills;
import com.djrapitops.plan.identification.ServerInfo;
import com.djrapitops.plan.identification.ServerUUID;
import com.djrapitops.plan.processing.Processing;
import com.djrapitops.plan.utilities.logging.ErrorLogger;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.playeranalytics.plan.gathering.listeners.fabric.DeathEventListener;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FabricCombatTest {
    @Test
    void fatalPlayerHitWinsOverPreviousMobDamageAndIsCountedOnce() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        UUID killerId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        ServerPlayer killer = mock(ServerPlayer.class);
        ServerPlayer victim = mock(ServerPlayer.class);
        when(killer.getUUID()).thenReturn(killerId);
        when(killer.getName()).thenReturn(Component.literal("Killer"));
        when(victim.getUUID()).thenReturn(victimId);
        when(victim.getName()).thenReturn(Component.literal("Victim"));
        ItemStack weapon = new ItemStack(Items.DIAMOND_SWORD);
        weapon.set(DataComponents.ITEM_NAME, Component.literal("Port Weapon"));
        when(killer.getMainHandItem()).thenReturn(weapon);
        DamageSource previous = mock(DamageSource.class);
        when(previous.getDirectEntity()).thenReturn(mock(Entity.class));
        when(victim.getLastDamageSource()).thenReturn(previous);
        DamageSource fatal = mock(DamageSource.class);
        when(fatal.getDirectEntity()).thenReturn(killer);
        when(fatal.getEntity()).thenReturn(killer);

        ActiveSession killerSession = new ActiveSession(killerId, ServerUUID.randomUUID(), 0, "minecraft:overworld", "SURVIVAL");
        ActiveSession victimSession = new ActiveSession(victimId, killerSession.getServerUUID(), 0, "minecraft:overworld", "SURVIVAL");
        SessionCache cache = new SessionCache();
        cache.cacheSession(killerId, killerSession);
        cache.cacheSession(victimId, victimSession);
        Processing processing = mock(Processing.class);
        when(processing.submitCritical(any(Runnable.class))).thenAnswer(call -> {
            call.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(true);
        });
        ErrorLogger errors = mock(ErrorLogger.class);
        DeathEventListener listener = new DeathEventListener(mock(ServerInfo.class), processing, errors);
        try {
            listener.register();
            // Vanilla invokes the combat event during die(), then AFTER_DEATH with the fatal source.
            ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.invoker().afterKilledOtherEntity(null, killer, victim);
            ServerLivingEntityEvents.AFTER_DEATH.invoker().afterDeath(victim, fatal);
            var kills = killerSession.getExtraData(PlayerKills.class).orElseThrow().asList();
            assertEquals(1, kills.size());
            assertEquals(victimId, kills.getFirst().victim().getUuid());
            assertEquals("Port Weapon", kills.getFirst().weapon());
            assertEquals(1, victimSession.toFinishedSession(1).getDeathCount());
            verifyNoInteractions(errors);
        } finally {
            listener.disable();
            SessionCache.clear();
        }
    }
}
