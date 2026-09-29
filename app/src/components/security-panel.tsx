import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, useWindowDimensions, View } from 'react-native';
import Animated, { useAnimatedStyle, withTiming } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { ThemedText } from '@/components/themed-text';
import { ThemedView } from '@/components/themed-view';
import { Spacing } from '@/constants/theme';
import {
  VerdictColor,
  VerdictGlyph,
  type ScannedLink,
  type SecurityEvaluation,
  type Verdict,
} from '@/lib/security';

type Props = { evaluation: SecurityEvaluation; links: ScannedLink[] };

function VerdictDot({ verdict, size = 18 }: { verdict: Verdict; size?: number }) {
  return (
    <View
      style={[
        styles.dot,
        { width: size, height: size, borderRadius: size / 2, backgroundColor: VerdictColor[verdict] },
      ]}>
      <ThemedText style={[styles.dotGlyph, { fontSize: size * 0.6, lineHeight: size }]}>
        {VerdictGlyph[verdict]}
      </ThemedText>
    </View>
  );
}

export function SecurityPanel({ evaluation, links }: Props) {
  const [open, setOpen] = useState(false);
  const { width } = useWindowDimensions();
  const insets = useSafeAreaInsets();
  const panelWidth = Math.min(width * 0.85, 360);

  const panelStyle = useAnimatedStyle(() => ({
    transform: [{ translateX: withTiming(open ? 0 : panelWidth, { duration: 220 }) }],
  }));
  const backdropStyle = useAnimatedStyle(() => ({
    opacity: withTiming(open ? 1 : 0, { duration: 220 }),
  }));

  return (
    <>
      <Pressable
        style={[styles.tab, { backgroundColor: VerdictColor[evaluation.verdict] }]}
        onPress={() => setOpen(true)}
        accessibilityRole="button"
        accessibilityLabel={`Security: ${evaluation.summary}`}>
        <ThemedText style={styles.tabIcon}>🛡️</ThemedText>
        <ThemedText style={styles.tabGlyph}>{VerdictGlyph[evaluation.verdict]}</ThemedText>
      </Pressable>

      <Animated.View
        style={[StyleSheet.absoluteFill, styles.backdrop, backdropStyle]}
        pointerEvents={open ? 'auto' : 'none'}>
        <Pressable style={StyleSheet.absoluteFill} onPress={() => setOpen(false)} />
      </Animated.View>

      <Animated.View style={[styles.panel, { width: panelWidth }, panelStyle]}>
        <ThemedView style={styles.panelInner}>
          <ScrollView contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + Spacing.four }]}>
            <View style={styles.headerRow}>
              <ThemedText type="smallBold" themeColor="textSecondary">
                SECURITY
              </ThemedText>
              <Pressable onPress={() => setOpen(false)} hitSlop={12}>
                <ThemedText type="linkPrimary">Close</ThemedText>
              </Pressable>
            </View>

            <View style={styles.summary}>
              <VerdictDot verdict={evaluation.verdict} size={28} />
              <ThemedText style={styles.summaryText}>{evaluation.summary}</ThemedText>
            </View>

            {evaluation.checks.map((check) => (
              <ThemedView key={check.label} type="backgroundElement" style={styles.card}>
                <VerdictDot verdict={check.verdict} />
                <View style={styles.cardText}>
                  <ThemedText type="smallBold">{check.label}</ThemedText>
                  <ThemedText type="small" themeColor="textSecondary">
                    {check.detail}
                  </ThemedText>
                </View>
              </ThemedView>
            ))}

            {links.length > 0 && (
              <>
                <ThemedText type="smallBold" themeColor="textSecondary" style={styles.sectionTitle}>
                  LINKS
                </ThemedText>
                {links.map((link) => (
                  <View key={link.url} style={styles.linkRow}>
                    <VerdictDot verdict={link.verdict} size={16} />
                    <View style={styles.cardText}>
                      <ThemedText type="small" numberOfLines={1}>
                        {link.host || link.url}
                      </ThemedText>
                      {link.reasons.map((reason) => (
                        <ThemedText key={reason} type="small" themeColor="textSecondary">
                          {reason}
                        </ThemedText>
                      ))}
                    </View>
                  </View>
                ))}
              </>
            )}
          </ScrollView>
        </ThemedView>
      </Animated.View>
    </>
  );
}

const styles = StyleSheet.create({
  tab: {
    position: 'absolute',
    right: 0,
    top: '40%',
    width: 32,
    paddingVertical: Spacing.three,
    alignItems: 'center',
    gap: Spacing.one,
    borderTopLeftRadius: Spacing.three,
    borderBottomLeftRadius: Spacing.three,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 4,
    shadowOffset: { width: -1, height: 1 },
    elevation: 4,
  },
  tabIcon: { fontSize: 14, lineHeight: 18 },
  tabGlyph: { color: '#fff', fontSize: 14, lineHeight: 18, fontWeight: 700 },
  backdrop: { backgroundColor: 'rgba(0,0,0,0.3)' },
  panel: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    right: 0,
    shadowColor: '#000',
    shadowOpacity: 0.25,
    shadowRadius: 12,
    elevation: 12,
  },
  panelInner: { flex: 1 },
  content: { padding: Spacing.three, gap: Spacing.three },
  headerRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  summary: { flexDirection: 'row', alignItems: 'center', gap: Spacing.two },
  summaryText: { flex: 1, fontSize: 18, fontWeight: 600 },
  card: {
    flexDirection: 'row',
    gap: Spacing.two,
    padding: Spacing.three,
    borderRadius: Spacing.three,
  },
  cardText: { flex: 1, gap: Spacing.half },
  sectionTitle: { marginTop: Spacing.two },
  linkRow: { flexDirection: 'row', gap: Spacing.two },
  dot: { alignItems: 'center', justifyContent: 'center' },
  dotGlyph: { color: '#fff', fontWeight: 700, textAlign: 'center' },
});
