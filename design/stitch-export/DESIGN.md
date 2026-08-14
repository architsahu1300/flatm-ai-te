---
name: Homley Petrol & Sand
colors:
  surface: '#fbf9f6'
  surface-dim: '#dbdad7'
  surface-bright: '#fbf9f6'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f5f3f0'
  surface-container: '#efeeeb'
  surface-container-high: '#eae8e5'
  surface-container-highest: '#e4e2df'
  on-surface: '#1b1c1a'
  on-surface-variant: '#3f484c'
  inverse-surface: '#30312f'
  inverse-on-surface: '#f2f0ed'
  outline: '#6f787d'
  outline-variant: '#bec8cd'
  surface-tint: '#006781'
  primary: '#005a71'
  on-primary: '#ffffff'
  primary-container: '#0e7490'
  on-primary-container: '#d3f1ff'
  inverse-primary: '#81d1f0'
  secondary: '#5e5e5d'
  on-secondary: '#ffffff'
  secondary-container: '#e1dfde'
  on-secondary-container: '#636362'
  tertiary: '#54534e'
  on-tertiary: '#ffffff'
  tertiary-container: '#6c6b66'
  on-tertiary-container: '#f0ece6'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#b9eaff'
  primary-fixed-dim: '#81d1f0'
  on-primary-fixed: '#001f29'
  on-primary-fixed-variant: '#004d62'
  secondary-fixed: '#e4e2e1'
  secondary-fixed-dim: '#c7c6c5'
  on-secondary-fixed: '#1b1c1b'
  on-secondary-fixed-variant: '#464746'
  tertiary-fixed: '#e5e2dc'
  tertiary-fixed-dim: '#c9c6c0'
  on-tertiary-fixed: '#1c1c18'
  on-tertiary-fixed-variant: '#484742'
  background: '#fbf9f6'
  on-background: '#1b1c1a'
  surface-variant: '#e4e2df'
  petrol: '#0E7490'
  sand-light: '#F7F5F2'
  sand-border: '#DFDCD6'
  onyx: '#1D262B'
  electric-cyan: '#22D3EE'
  deep-void: '#0B1114'
typography:
  display-lg:
    fontFamily: Inter
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  display-lg-mobile:
    fontFamily: Inter
    fontSize: 28px
    fontWeight: '700'
    lineHeight: 36px
    letterSpacing: -0.01em
  headline-lg:
    fontFamily: Inter
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  title-md:
    fontFamily: Inter
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '400'
    lineHeight: 28px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  label-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '500'
    lineHeight: 20px
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  stack-xs: 4px
  stack-sm: 8px
  stack-md: 16px
  stack-lg: 24px
  stack-xl: 48px
  gutter: 24px
  margin-mobile: 20px
  margin-desktop: 80px
  container-max: 1280px
---

## Brand & Style
Homley embodies a "Sophisticated Coastal" aesthetic, blending the precision of a modern SaaS tool with a warm, organic color palette. The brand personality is professional, reliable, and approachable, targeting high-end real estate or lifestyle services.

The design style is **Corporate Modern** with a focus on high-quality utility. It utilizes a structured layout, generous whitespace, and subtle tactile cues (like soft shadows) to create a sense of order and premium quality. The interface prioritizes clarity and ease of use through systematic typography and a clear hierarchy of elements.

## Colors
The palette is built on the contrast between **Petrol** (Primary) and **Sand** (Neutral). 

- **Primary (Petrol):** Used for primary actions, branding, and active states to convey trust and depth.
- **Neutral (Sand):** A warm, off-white foundation that prevents the UI from feeling sterile.
- **Functional Colors:** Success (Emerald) and Error (Deep Red) are used sparingly for status communication.
- **Dark Mode:** A complete inversion using "Deep Void" as the base and "Electric Cyan" as the primary accent to maintain high contrast and energy in low-light environments.

## Typography
The system uses **Inter** exclusively to ensure maximum legibility and a clean, utilitarian feel. 

Hierarchy is established through tight control over weights (Regular, Medium, Semi-Bold, Bold) and negative letter spacing on larger display sizes to maintain a compact, professional look. Tabular numbers are enabled by default for price displays and data-heavy contexts.

## Layout & Spacing
The layout follows a **Fixed-Fluid Hybrid** model. Content is contained within a 1280px max-width wrapper on desktop, with generous 80px side margins. 

Vertical rhythm is managed through a strict "stack" scale. Small components use 4px/8px gaps, while major sections are separated by 48px to provide visual breathing room. A 24px gutter is maintained for internal grid components.

## Elevation & Depth
The system uses **Tonal Layering** combined with **Ambient Shadows**. 

- **Surface Tiers:** Backgrounds use `surface`, while interactive containers use `surface-container-lowest` (pure white) to appear physically raised.
- **Shadows:** 
  - *Whisper:* A very subtle shadow (4px blur, 4% opacity) for standard buttons and inputs.
  - *Soft-Pop:* A more pronounced shadow (24px blur, 8% opacity) for elevated elements like search boxes or dropdowns.
- **Interactions:** Use scale transforms (95% on click) rather than heavy elevation changes to maintain a modern, flat-ish feel.

## Shapes
The shape language is consistently **Rounded**, using `0.75rem` (12px) as the standard radius for buttons and input fields. 

Larger containers such as cards should scale up to `1rem` or `1.5rem` to maintain visual harmony with the inner elements. Small elements like keyboard shortcuts (⌘K) use a more compact 4px radius.

## Components
- **Buttons:** Primary buttons are solid Petrol with white text. Outline buttons use a `sand-border` with dark text. Ghost buttons use Primary text and an low-opacity primary background on hover.
- **Inputs:** Text fields use the `lowest` surface tier (white) with a `sand-border`. Focus states are indicated by a 2px Primary ring and the removal of the border.
- **Search Boxes:** Enhanced inputs that feature "Soft-Pop" shadows and secondary "K-tag" shortcuts to distinguish them from standard form fields.
- **Chips/Badges:** Use a subtle background fill (Surface Tier 2) with Label-Medium typography, maintaining the standard 12px corner radius.
- **Cards:** White containers with "Whisper" shadows, 24px internal padding, and 16px corner radius.