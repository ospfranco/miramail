import { useLocalSearchParams } from 'expo-router';
import * as WebBrowser from 'expo-web-browser';
import { useEffect, useState } from 'react';
import { ActivityIndicator, Alert, Linking, StyleSheet } from 'react-native';

import { EmailBody } from '@/components/email-body';
import { SecurityPanel } from '@/components/security-panel';
import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { Spacing } from '@/constants/theme';
import { getMessage, type MessageDetail } from '@/lib/gmail';
import {
  evaluateEmail,
  scanLinks,
  type FoundLink,
  type ScannedLink,
  type SecurityEvaluation,
} from '@/lib/security';

export default function MessageScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const [message, setMessage] = useState<MessageDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [links, setLinks] = useState<ScannedLink[] | null>(null);
  const [evaluation, setEvaluation] = useState<SecurityEvaluation | null>(null);

  useEffect(() => {
    getMessage(id)
      .then(setMessage)
      .catch((e) => setError(String(e)));
  }, [id]);

  const handleLinksFound = async (found: FoundLink[]) => {
    if (!message) return;
    const scanned = await scanLinks(found);
    setLinks(scanned);
    setEvaluation(evaluateEmail(message, scanned));
  };

  const openLink = (url: string) => {
    if (/^https?:/i.test(url)) WebBrowser.openBrowserAsync(url);
    else Linking.openURL(url).catch(() => Alert.alert("Can't open this link", url));
  };

  const handleLinkPress = (url: string) => {
    const link = links?.find((l) => l.url === url);
    if (!link || link.verdict === 'safe') return openLink(url);

    Alert.alert(
      link.verdict === 'danger' ? 'This link looks unsafe' : 'Be careful with this link',
      [link.host || url, ...link.reasons].join('\n\n'),
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Open anyway', style: 'destructive', onPress: () => openLink(url) },
      ],
    );
  };

  if (error) {
    return (
      <ThemedView style={styles.centered}>
        <ThemedText type="small">{error}</ThemedText>
      </ThemedView>
    );
  }

  if (!message) {
    return (
      <ThemedView style={styles.centered}>
        <ActivityIndicator />
      </ThemedView>
    );
  }

  return (
    <ThemedView style={styles.container}>
      <ThemedView style={styles.header}>
        <ThemedText type="default" style={styles.subject}>
          {message.subject}
        </ThemedText>
        <ThemedText type="small" numberOfLines={1}>
          {message.from}
        </ThemedText>
        <ThemedText type="small" themeColor="textSecondary" numberOfLines={1}>
          {message.date}
        </ThemedText>
        {!evaluation && (
          <ThemedText type="small" themeColor="textSecondary">
            Scanning links…
          </ThemedText>
        )}
      </ThemedView>

      <EmailBody
        body={message.body}
        bodyType={message.bodyType}
        scannedLinks={links}
        onLinksFound={handleLinksFound}
        onLinkPress={handleLinkPress}
      />

      {evaluation && links && <SecurityPanel evaluation={evaluation} links={links} />}
    </ThemedView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: Spacing.four },
  header: {
    paddingHorizontal: Spacing.three,
    paddingVertical: Spacing.three,
    gap: Spacing.half,
  },
  subject: { fontSize: 20, lineHeight: 26, fontWeight: 600 },
});
