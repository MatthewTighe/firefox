# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this file,
# You can obtain one at http://mozilla.org/MPL/2.0/.

# This script generates properties/descriptor binding webidl based on
# ServoCSSPropList.

import runpy


# Generates a line of WebIDL with the given spelling of the property name
# (whether camelCase, _underscorePrefixed, etc.) and the given array of
# extended attributes.
def generateLine(propName, extendedAttrs):
    return "  [%s] attribute [LegacyNullToEmptyString] UTF8String %s;\n" % (
        ", ".join(extendedAttrs),
        propName,
    )


def generate(output, dataFile, ruleType, interfaceName, bindingTemplate, pref=None):
    propsData = runpy.run_path(dataFile)["data"]
    output.write(
        """/* THIS IS AN AUTOGENERATED FILE.  DO NOT EDIT */

/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

[Exposed=Window"""
    )
    if pref:
        output.write(', Pref="' + pref + '"')
    output.write(
        """]
interface """
        + interfaceName
        + " : CSSStyleDeclaration {\n"
    )
    for p in propsData.values():
        # Skip properties which aren't valid in style rules.
        if ruleType not in p.rules:
            continue

        pref = p.pref

        if p.type() == "alias":
            if p.method == "MozAppearance":
                # Hide MozAppearance from CSSStyleProperties to prevent outdated
                # special casing against Gecko. (Bug 1977489)
                pref = "layout.css.moz-appearance.webidl.enabled"
            elif p.pref == propsData[p.prop_id].pref:
                # We already added this as a BindingAlias for the original prop.
                continue

            propId = p.prop_id
        else:
            propId = p.id
        extendedAttrs = [
            "BindingTemplate=(%s, eCSSProperty_%s)" % (bindingTemplate, propId),
            "CEReactions",
            "SetterThrows",
            "SetterNeedsSubjectPrincipal=NonSystem",
        ]

        if pref != "":
            assert "Internal" not in p.flags
            # BackdropFilter is a special case where we want WebIDL to check
            # a function instead of checking the pref directly.
            if p.method == "BackdropFilter":
                extendedAttrs.append('Func="nsCSSProps::IsBackdropFilterAvailable"')
            else:
                extendedAttrs.append('Pref="%s"' % pref)
        elif "EnabledInUASheetsAndChrome" in p.flags:
            extendedAttrs.append("ChromeOnly")
        elif "Internal" in p.flags:
            continue

        def add_extra_accessors(p):
            prop = p.method

            # webkit properties get a camelcase "webkitFoo" accessor
            # as well as a capitalized "WebkitFoo" alias (added here).
            if prop.startswith("Webkit"):
                extendedAttrs.append('BindingAlias="%s"' % prop)

            # Generate a name with camelCase spelling of property-name (or capitalized,
            # for Moz-prefixed properties):
            if not prop.startswith("Moz"):
                prop = prop[0].lower() + prop[1:]

            # Per spec, what's actually supposed to happen here is that we're supposed
            # to have properties for:
            #
            # 1) Each supported CSS property name, camelCased.
            # 2) Each supported name that contains or starts with dashes,
            #    without any changes to the name.
            # 3) cssFloat
            #
            # Note that "float" will cause a property called "float" to exist due to (1)
            # in that list.
            #
            # In practice, cssFloat is the only case in which "name" doesn't contain
            # "-" but also doesn't match "prop".  So the generateLine() call will
            # cover (3) and all of (1) except "float".  If we now add an alias
            # for all the cases where "name" doesn't match "prop", that will cover
            # "float" and (2).
            if prop != p.name:
                extendedAttrs.append('BindingAlias="%s"' % p.name)

            return prop

        prop = add_extra_accessors(p)

        if p.type() != "alias":
            for a in p.aliases:
                if p.pref == propsData[a].pref:
                    newProp = add_extra_accessors(propsData[a])
                    extendedAttrs.append('BindingAlias="%s"' % newProp)

        output.write(generateLine(prop, extendedAttrs))

    output.write("};")


def generateCSS2Properties(output, dataFile):
    generate(output, dataFile, "Style", "CSS2Properties", "CSS2Property")


def generateCSSPageDescriptors(output, dataFile):
    generate(
        output,
        dataFile,
        "Page",
        "CSSPageDescriptors",
        "CSSPageDescriptor",
    )


def generateCSSPositionTryDescriptors(output, dataFile):
    generate(
        output,
        dataFile,
        "PositionTry",
        "CSSPositionTryDescriptors",
        "CSSPositionTryDescriptor",
        "layout.css.anchor-positioning.enabled",
    )
