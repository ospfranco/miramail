import type { User } from '@react-native-google-signin/google-signin';
import { router } from 'expo-router';
import { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { BottomTabInset, MaxContentWidth, Spacing } from '@/constants/theme';
import { listInbox, type MessageSummary } from '@/lib/gmail';
import { restoreSession, signIn, signOut } from '@/lib/google-auth';

export default function InboxScreen() {
  const [user, setUser] = useState<User | null>(null);
  const [messages, setMessages] = useState<MessageSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadInbox = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setMessages(await listInbox());
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    restoreSession()
      .then((u) => {
        setUser(u);
        if (u) return loadInbox();
      })
      .catch((e) => setError(String(e)))
      .finally(() => setLoading(false));
  }, [loadInbox]);

  const handleSignIn = async () => {
    setError(null);
    try {
      const u = await signIn();
      setUser(u);
      if (u) await loadInbox();
    } catch (e) {
      setError(String(e));
    }
  };

  const handleSignOut = async () => {
    await signOut();
    setUser(null);
    setMessages([]);
  };

  return (
    <ThemedView style={styles.container}>
      <SafeAreaView style={styles.safeArea}>
        {!user ? (
          <ThemedView style={styles.centered}>
            <ThemedText type="subtitle">safemail</ThemedText>
            {loading ? (
              <ActivityIndicator />
            ) : (
              <Pressable onPress={handleSignIn}>
                <ThemedView type="backgroundElement" style={styles.button}>
                  <ThemedText>Sign in with Google</ThemedText>
                </ThemedView>
              </Pressable>
            )}
            {error && <ThemedText type="small">{error}</ThemedText>}
          </ThemedView>
        ) : (
          <>
            <ThemedView style={styles.header}>
              <ThemedText type="smallBold">{user.user.email}</ThemedText>
              <Pressable onPress={handleSignOut}>
                <ThemedText type="linkPrimary">Sign out</ThemedText>
              </Pressable>
            </ThemedView>
            {error && <ThemedText type="small">{error}</ThemedText>}
            <FlatList
              data={messages}
              keyExtractor={(m) => m.id}
              refreshControl={<RefreshControl refreshing={loading} onRefresh={loadInbox} />}
              renderItem={({ item }) => (
                <Pressable
                  onPress={() => router.push({ pathname: '/message/[id]', params: { id: item.id } })}>
                  <ThemedView style={styles.row}>
                    <ThemedText type={item.unread ? 'smallBold' : 'small'} numberOfLines={1}>
                      {item.from}
                    </ThemedText>
                    <ThemedText type={item.unread ? 'smallBold' : 'small'} numberOfLines={1}>
                      {item.subject}
                    </ThemedText>
                    <ThemedText type="small" themeColor="textSecondary" numberOfLines={2}>
                      {item.snippet}
                    </ThemedText>
                  </ThemedView>
                </Pressable>
              )}
            />
          </>
        )}
      </SafeAreaView>
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    flexDirection: 'row',
    justifyContent: 'center',
  },
  safeArea: {
    flex: 1,
    paddingHorizontal: Spacing.three,
    paddingBottom: BottomTabInset,
    maxWidth: MaxContentWidth,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    gap: Spacing.four,
  },
  button: {
    paddingHorizontal: Spacing.four,
    paddingVertical: Spacing.three,
    borderRadius: Spacing.three,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: Spacing.three,
  },
  row: {
    paddingVertical: Spacing.three,
    gap: Spacing.half,
  },
});
