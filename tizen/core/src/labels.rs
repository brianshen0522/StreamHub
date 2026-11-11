//! How episode and season labels are *shown*. Display only: a label is an
//! opaque key (shared/api/README.md) — progress, favourites and /api/sources
//! all use the raw label, so nothing here may ever be used as a key.

/// An episode label for the screen. Bare digits become 「第N集」, keeping the
/// provider's zero padding ("01" → 「第01集」); anything else ("第04集",
/// "第37集已完結", "EP1", "預告片", "HD中字") is shown as given.
pub fn episode_caption(label: &str) -> String {
    let t = label.trim();
    if !t.is_empty() && t.chars().all(|c| c.is_ascii_digit()) {
        format!("第{t}集")
    } else {
        t.to_string()
    }
}

fn chinese_number(s: &str) -> Option<u32> {
    let digit = |c: char| "零一二三四五六七八九".chars().position(|d| d == c).map(|p| p as u32);
    let chars: Vec<char> = s.chars().collect();
    match chars.as_slice() {
        [] => None,
        ['十'] => Some(10),
        ['十', d] => Some(10 + digit(*d)?),
        [t, '十'] => Some(digit(*t)? * 10),
        [t, '十', d] => Some(digit(*t)? * 10 + digit(*d)?),
        [d] => digit(*d),
        _ => None,
    }
}

/// Max characters of a season label shown when no number can be found.
const FALLBACK_CHARS: usize = 14;

/// A season label split for a pill: the main line 「第 N 季」 and the rest of
/// the site's text (year, update status) for a quieter second line. Labels
/// with no recognisable number come back truncated at 14 characters with an
/// ellipsis, and no second line.
///
/// Recognised: "Season 2 201510集全", "season 3", "第3季", "第三季", "S02",
/// "S2 2019".
pub fn season_parts(label: &str) -> (String, String) {
    let t = label.trim();
    let main = |n: u32| format!("第 {n} 季");
    let lower = t.to_ascii_lowercase();

    // "Season N …"
    if let Some(pos) = lower.find("season") {
        let after = &t[pos + "season".len()..];
        let digits: String = after.trim_start().chars().take_while(|c| c.is_ascii_digit()).collect();
        if let Ok(n) = digits.parse::<u32>() {
            let skip = after.len() - after.trim_start().len() + digits.len();
            let rest = format!("{}{}", &t[..pos], &after[skip..]);
            return (main(n), rest.trim().to_string());
        }
    }
    // "第N季" / "第三季"
    if let (Some(start), Some(end)) = (t.find('第'), t.find('季')) {
        if start < end {
            let inner = &t[start + '第'.len_utf8()..end];
            let n = inner.trim().parse::<u32>().ok().or_else(|| chinese_number(inner.trim()));
            if let Some(n) = n {
                let rest = format!("{}{}", &t[..start], &t[end + '季'.len_utf8()..]);
                return (main(n), rest.trim().to_string());
            }
        }
    }
    // "S02" as its own token.
    let mut words = t.split_whitespace();
    if let Some(first) = words.next() {
        let mut cs = first.chars();
        if matches!(cs.next(), Some('S' | 's')) {
            let digits: String = cs.collect();
            if !digits.is_empty() && digits.chars().all(|c| c.is_ascii_digit()) {
                if let Ok(n) = digits.parse::<u32>() {
                    return (main(n), words.collect::<Vec<_>>().join(" "));
                }
            }
        }
    }
    let chars: Vec<char> = t.chars().collect();
    if chars.len() > FALLBACK_CHARS {
        (format!("{}…", chars[..FALLBACK_CHARS].iter().collect::<String>()), String::new())
    } else {
        (t.to_string(), String::new())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bare_digits_get_the_episode_word_and_keep_their_padding() {
        assert_eq!(episode_caption("1"), "第1集");
        assert_eq!(episode_caption("01"), "第01集");
        assert_eq!(episode_caption(" 12 "), "第12集");
    }

    #[test]
    fn labels_with_words_are_shown_as_given() {
        for label in ["第04集", "第37集已完結", "EP1", "預告片", "HD中字", "全", "第5期", "第3話"] {
            assert_eq!(episode_caption(label), label, "{label}");
        }
        assert_eq!(episode_caption(""), "");
    }

    #[test]
    fn season_numbers_are_pulled_out_of_site_labels() {
        assert_eq!(season_parts("Season 2 201510集全"), ("第 2 季".into(), "201510集全".into()));
        assert_eq!(season_parts("Season 1 2013更新第11集"), ("第 1 季".into(), "2013更新第11集".into()));
        assert_eq!(season_parts("Season 1 2022已完結 共8集"), ("第 1 季".into(), "2022已完結 共8集".into()));
        assert_eq!(season_parts("season 3"), ("第 3 季".into(), "".into()));
        assert_eq!(season_parts("第3季"), ("第 3 季".into(), "".into()));
        assert_eq!(season_parts("黑暗榮耀 第二季"), ("第 2 季".into(), "黑暗榮耀".into()));
        assert_eq!(season_parts("第十二季 完結"), ("第 12 季".into(), "完結".into()));
        assert_eq!(season_parts("S02"), ("第 2 季".into(), "".into()));
        assert_eq!(season_parts("S2 2019"), ("第 2 季".into(), "2019".into()));
    }

    #[test]
    fn unrecognised_season_labels_are_truncated() {
        assert_eq!(season_parts("特別篇"), ("特別篇".into(), "".into()));
        let (main, rest) = season_parts("這是一個非常非常長的季標籤沒有任何數字可以抓");
        assert_eq!(main.chars().count(), 15);
        assert!(main.ends_with('…'));
        assert_eq!(rest, "");
        // "Super" starts with S but is not a season token.
        assert_eq!(season_parts("Super"), ("Super".into(), "".into()));
    }
}
