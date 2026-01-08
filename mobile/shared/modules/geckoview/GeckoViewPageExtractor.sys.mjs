/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

const lazy = {};

ChromeUtils.defineESModuleGetters(lazy, {
  PageExtractorParent: "resource://gre/actors/PageExtractorParent.sys.mjs",
});

import { GeckoViewModule } from "resource://gre/modules/GeckoViewModule.sys.mjs";

export class GeckoViewPageExtractor extends GeckoViewModule {
  onInit() {
    debug`onInit`;
    this.registerListener(["GeckoView:PageExtractor:GetText"]);
  }

  onEvent(aEvent, aData, aCallback) {
    debug`onEvent: event=${aEvent}, data=${aData}`;
    switch (aEvent) {
      case "GeckoView:PageExtractor:GetText": {
        // async function _getPageText() {
        //   return lazy.PageExtractor.getText()
        // }

        try {
          lazy.PageExtractorParent.getHeadlessExtractor(
            aData.url,
            pageExtractor => {
              const result = pageExtractor.getText();
              return { text: result };
            }
          );

          aCallback.onSuccess("");
        } catch (error) {
          aCallback.onError(`Could not get language setting: ${error}`);
        }
        break;
      }
    }
  }
}

const { debug, warn } = GeckoViewPageExtractor.initLogging(
  "GeckoViewPageExtractor"
);
