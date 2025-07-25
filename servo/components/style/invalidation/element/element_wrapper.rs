/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! A wrapper over an element and a snapshot, that allows us to selector-match
//! against a past state of the element.

use crate::dom::TElement;
use crate::selector_parser::{AttrValue, NonTSPseudoClass, PseudoElement, SelectorImpl};
use crate::selector_parser::{Snapshot, SnapshotMap};
use crate::values::AtomIdent;
use crate::{CaseSensitivityExt, LocalName, Namespace, WeakAtom};
use dom::ElementState;
use selectors::attr::{AttrSelectorOperation, CaseSensitivity, NamespaceConstraint};
use selectors::bloom::BloomFilter;
use selectors::matching::{ElementSelectorFlags, MatchingContext};
use selectors::{Element, OpaqueElement};
use std::cell::Cell;
use std::fmt;

/// In order to compute restyle hints, we perform a selector match against a
/// list of partial selectors whose rightmost simple selector may be sensitive
/// to the thing being changed. We do this matching twice, once for the element
/// as it exists now and once for the element as it existed at the time of the
/// last restyle. If the results of the selector match differ, that means that
/// the given partial selector is sensitive to the change, and we compute a
/// restyle hint based on its combinator.
///
/// In order to run selector matching against the old element state, we generate
/// a wrapper for the element which claims to have the old state. This is the
/// ElementWrapper logic below.
///
/// Gecko does this differently for element states, and passes a mask called
/// mStateMask, which indicates the states that need to be ignored during
/// selector matching. This saves an ElementWrapper allocation and an additional
/// selector match call at the expense of additional complexity inside the
/// selector matching logic. This only works for boolean states though, so we
/// still need to take the ElementWrapper approach for attribute-dependent
/// style. So we do it the same both ways for now to reduce complexity, but it's
/// worth measuring the performance impact (if any) of the mStateMask approach.
pub trait ElementSnapshot: Sized {
    /// The state of the snapshot, if any.
    fn state(&self) -> Option<ElementState>;

    /// If this snapshot contains attribute information.
    fn has_attrs(&self) -> bool;

    /// Gets the attribute information of the snapshot as a string.
    ///
    /// Only for debugging purposes.
    fn debug_list_attributes(&self) -> String {
        String::new()
    }

    /// The ID attribute per this snapshot. Should only be called if
    /// `has_attrs()` returns true.
    fn id_attr(&self) -> Option<&WeakAtom>;

    /// Whether this snapshot contains the class `name`. Should only be called
    /// if `has_attrs()` returns true.
    fn has_class(&self, name: &AtomIdent, case_sensitivity: CaseSensitivity) -> bool;

    /// Whether this snapshot represents the part named `name`. Should only be
    /// called if `has_attrs()` returns true.
    fn is_part(&self, name: &AtomIdent) -> bool;

    /// See Element::imported_part.
    fn imported_part(&self, name: &AtomIdent) -> Option<AtomIdent>;

    /// A callback that should be called for each class of the snapshot. Should
    /// only be called if `has_attrs()` returns true.
    fn each_class<F>(&self, _: F)
    where
        F: FnMut(&AtomIdent);

    /// If this snapshot contains CustomStateSet information.
    fn has_custom_states(&self) -> bool;

    /// A callback that should be called for each CustomState of the snapshot.
    fn has_custom_state(&self, state: &AtomIdent) -> bool;

    /// A callback that should be called for each CustomState of the snapshot.
    fn each_custom_state<F>(&self, callback: F)
    where
        F: FnMut(&AtomIdent);

    /// The `xml:lang=""` or `lang=""` attribute value per this snapshot.
    fn lang_attr(&self) -> Option<AttrValue>;
}

/// A simple wrapper over an element and a snapshot, that allows us to
/// selector-match against a past state of the element.
#[derive(Clone)]
pub struct ElementWrapper<'a, E>
where
    E: TElement,
{
    element: E,
    cached_snapshot: Cell<Option<&'a Snapshot>>,
    snapshot_map: &'a SnapshotMap,
}

impl<'a, E> ElementWrapper<'a, E>
where
    E: TElement,
{
    /// Trivially constructs an `ElementWrapper`.
    pub fn new(el: E, snapshot_map: &'a SnapshotMap) -> Self {
        ElementWrapper {
            element: el,
            cached_snapshot: Cell::new(None),
            snapshot_map: snapshot_map,
        }
    }

    /// Gets the snapshot associated with this element, if any.
    pub fn snapshot(&self) -> Option<&'a Snapshot> {
        if !self.element.has_snapshot() {
            return None;
        }

        if let Some(s) = self.cached_snapshot.get() {
            return Some(s);
        }

        let snapshot = self.snapshot_map.get(&self.element);
        debug_assert!(snapshot.is_some(), "has_snapshot lied!");

        self.cached_snapshot.set(snapshot);

        snapshot
    }

    /// Returns the states that have changed since the element was snapshotted.
    pub fn state_changes(&self) -> ElementState {
        let snapshot = match self.snapshot() {
            Some(s) => s,
            None => return ElementState::empty(),
        };

        match snapshot.state() {
            Some(state) => state ^ self.element.state(),
            None => ElementState::empty(),
        }
    }

    /// Returns the value of the `xml:lang=""` (or, if appropriate, `lang=""`)
    /// attribute from this element's snapshot or the closest ancestor
    /// element snapshot with the attribute specified.
    fn get_lang(&self) -> Option<AttrValue> {
        let mut current = self.clone();
        loop {
            let lang = match self.snapshot() {
                Some(snapshot) if snapshot.has_attrs() => snapshot.lang_attr(),
                _ => current.element.lang_attr(),
            };
            if lang.is_some() {
                return lang;
            }
            current = current.parent_element()?;
        }
    }
}

impl<'a, E> fmt::Debug for ElementWrapper<'a, E>
where
    E: TElement,
{
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        // Ignore other fields for now, can change later if needed.
        self.element.fmt(f)
    }
}

impl<'a, E> Element for ElementWrapper<'a, E>
where
    E: TElement,
{
    type Impl = SelectorImpl;

    fn match_non_ts_pseudo_class(
        &self,
        pseudo_class: &NonTSPseudoClass,
        context: &mut MatchingContext<Self::Impl>,
    ) -> bool {
        // Some pseudo-classes need special handling to evaluate them against
        // the snapshot.
        match *pseudo_class {
            // For :link and :visited, we don't actually want to test the
            // element state directly.
            //
            // Instead, we use the `visited_handling` to determine if they
            // match.
            NonTSPseudoClass::Link => {
                return self.is_link() && context.visited_handling().matches_unvisited();
            },
            NonTSPseudoClass::Visited => {
                return self.is_link() && context.visited_handling().matches_visited();
            },

            #[cfg(feature = "gecko")]
            NonTSPseudoClass::MozTableBorderNonzero => {
                if let Some(snapshot) = self.snapshot() {
                    if snapshot.has_other_pseudo_class_state() {
                        return snapshot.mIsTableBorderNonzero();
                    }
                }
            },

            #[cfg(feature = "gecko")]
            NonTSPseudoClass::MozSelectListBox => {
                if let Some(snapshot) = self.snapshot() {
                    if snapshot.has_other_pseudo_class_state() {
                        return snapshot.mIsSelectListBox();
                    }
                }
            },

            // :lang() needs to match using the closest ancestor xml:lang="" or
            // lang="" attribtue from snapshots.
            NonTSPseudoClass::Lang(ref lang_arg) => {
                return self
                    .element
                    .match_element_lang(Some(self.get_lang()), lang_arg);
            },

            // :heading should match against snapshot before element
            NonTSPseudoClass::Heading(ref levels) => {
                return levels.matches_state(
                    self.snapshot()
                        .and_then(|s| s.state())
                        .unwrap_or_else(|| self.element.state()),
                );
            },

            // CustomStateSet should match against the snapshot before element
            NonTSPseudoClass::CustomState(ref state) => return self.has_custom_state(&state.0),

            _ => {},
        }

        let flag = pseudo_class.state_flag();
        if flag.is_empty() {
            return self
                .element
                .match_non_ts_pseudo_class(pseudo_class, context);
        }
        match self.snapshot().and_then(|s| s.state()) {
            Some(snapshot_state) => snapshot_state.intersects(flag),
            None => self
                .element
                .match_non_ts_pseudo_class(pseudo_class, context),
        }
    }

    fn apply_selector_flags(&self, _flags: ElementSelectorFlags) {
        debug_assert!(false, "Shouldn't need selector flags for invalidation");
    }

    fn match_pseudo_element(
        &self,
        pseudo_element: &PseudoElement,
        context: &mut MatchingContext<Self::Impl>,
    ) -> bool {
        self.element.match_pseudo_element(pseudo_element, context)
    }

    fn is_link(&self) -> bool {
        match self.snapshot().and_then(|s| s.state()) {
            Some(state) => state.intersects(ElementState::VISITED_OR_UNVISITED),
            None => self.element.is_link(),
        }
    }

    fn opaque(&self) -> OpaqueElement {
        self.element.opaque()
    }

    fn parent_element(&self) -> Option<Self> {
        let parent = self.element.parent_element()?;
        Some(Self::new(parent, self.snapshot_map))
    }

    fn parent_node_is_shadow_root(&self) -> bool {
        self.element.parent_node_is_shadow_root()
    }

    fn containing_shadow_host(&self) -> Option<Self> {
        let host = self.element.containing_shadow_host()?;
        Some(Self::new(host, self.snapshot_map))
    }

    fn prev_sibling_element(&self) -> Option<Self> {
        let sibling = self.element.prev_sibling_element()?;
        Some(Self::new(sibling, self.snapshot_map))
    }

    fn next_sibling_element(&self) -> Option<Self> {
        let sibling = self.element.next_sibling_element()?;
        Some(Self::new(sibling, self.snapshot_map))
    }

    fn first_element_child(&self) -> Option<Self> {
        let child = self.element.first_element_child()?;
        Some(Self::new(child, self.snapshot_map))
    }

    #[inline]
    fn is_html_element_in_html_document(&self) -> bool {
        self.element.is_html_element_in_html_document()
    }

    #[inline]
    fn is_html_slot_element(&self) -> bool {
        self.element.is_html_slot_element()
    }

    #[inline]
    fn has_local_name(
        &self,
        local_name: &<Self::Impl as ::selectors::SelectorImpl>::BorrowedLocalName,
    ) -> bool {
        self.element.has_local_name(local_name)
    }

    #[inline]
    fn has_namespace(
        &self,
        ns: &<Self::Impl as ::selectors::SelectorImpl>::BorrowedNamespaceUrl,
    ) -> bool {
        self.element.has_namespace(ns)
    }

    #[inline]
    fn is_same_type(&self, other: &Self) -> bool {
        self.element.is_same_type(&other.element)
    }

    fn attr_matches(
        &self,
        ns: &NamespaceConstraint<&Namespace>,
        local_name: &LocalName,
        operation: &AttrSelectorOperation<&AttrValue>,
    ) -> bool {
        match self.snapshot() {
            Some(snapshot) if snapshot.has_attrs() => {
                snapshot.attr_matches(ns, local_name, operation)
            },
            _ => self.element.attr_matches(ns, local_name, operation),
        }
    }

    fn has_id(&self, id: &AtomIdent, case_sensitivity: CaseSensitivity) -> bool {
        match self.snapshot() {
            Some(snapshot) if snapshot.has_attrs() => snapshot
                .id_attr()
                .map_or(false, |atom| case_sensitivity.eq_atom(&atom, id)),
            _ => self.element.has_id(id, case_sensitivity),
        }
    }

    fn is_part(&self, name: &AtomIdent) -> bool {
        match self.snapshot() {
            Some(snapshot) if snapshot.has_attrs() => snapshot.is_part(name),
            _ => self.element.is_part(name),
        }
    }

    fn imported_part(&self, name: &AtomIdent) -> Option<AtomIdent> {
        match self.snapshot() {
            Some(snapshot) if snapshot.has_attrs() => snapshot.imported_part(name),
            _ => self.element.imported_part(name),
        }
    }

    fn has_class(&self, name: &AtomIdent, case_sensitivity: CaseSensitivity) -> bool {
        match self.snapshot() {
            Some(snapshot) if snapshot.has_attrs() => snapshot.has_class(name, case_sensitivity),
            _ => self.element.has_class(name, case_sensitivity),
        }
    }

    fn has_custom_state(&self, state: &AtomIdent) -> bool {
        match self.snapshot() {
            Some(snapshot) if snapshot.has_custom_states() => snapshot.has_custom_state(state),
            _ => self.element.has_custom_state(state),
        }
    }

    fn is_empty(&self) -> bool {
        self.element.is_empty()
    }

    fn is_root(&self) -> bool {
        self.element.is_root()
    }

    fn is_pseudo_element(&self) -> bool {
        self.element.is_pseudo_element()
    }

    fn pseudo_element_originating_element(&self) -> Option<Self> {
        self.element
            .pseudo_element_originating_element()
            .map(|e| ElementWrapper::new(e, self.snapshot_map))
    }

    fn assigned_slot(&self) -> Option<Self> {
        self.element
            .assigned_slot()
            .map(|e| ElementWrapper::new(e, self.snapshot_map))
    }

    fn add_element_unique_hashes(&self, _filter: &mut BloomFilter) -> bool {
        // Should not be relevant in the context of checking past elements in invalidation.
        false
    }
}
