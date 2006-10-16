// Copyright (C) 2006 Google Inc.
// 
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// 
//      http://www.apache.org/licenses/LICENSE-2.0
// 
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.ical.iter;

import com.google.ical.values.DateTimeValue;
import com.google.ical.values.DateTimeValueImpl;
import com.google.ical.values.DateValue;
import com.google.ical.values.Frequency;
import com.google.ical.values.IcalObject;
import com.google.ical.values.RDateList;
import com.google.ical.values.RRule;
import com.google.ical.values.TimeValue;
import com.google.ical.values.Weekday;
import com.google.ical.values.WeekdayNum;
import com.google.ical.util.Predicate;
import com.google.ical.util.Predicates;
import com.google.ical.util.TimeUtils;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * for calculating the occurrences of an individual RFC 2445 RRULE.
 *
 * <h4>Glossary</h4>
 * Period - year|month|day|...<br>
 * Day of the week - an int in [0-6].  See RRULE_WDAY_* in rrule.js<br>
 * Day of the year - zero indexed in [0,365]<br>
 * Day of the month - 1 indexed in [1,31]<br>
 * Month - 1 indexed integer in [1,12]
 *
 * <h4>Abstractions</h4>
 * Generator - a function corresponding to an RRULE part that takes a date and
 *   returns a later (year or month or day depending on its period) within the
 *   next larger period.
 *   A generator ignores all periods in its input smaller than its period.
 * <p>
 * Filter - a function that returns true iff the given date matches the subrule.
 * <p>
 * Condition - returns true if the given date is past the end of the recurrence.
 *
 * <p>All the functions that represent rule parts are stateful.
 *
 * @author msamuel@google.com (Mike Samuel)
 */
public class RecurrenceIteratorFactory {

  private static final Logger LOGGER = Logger.getLogger(
      RecurrenceIteratorFactory.class.getName());

  /**
   * given a block of RRULE, EXRULE, RDATE, and EXDATE content lines, parse
   * them into a single recurrence iterator.
   * @param strict true if any failure to parse should result in a
   *   ParseException.  false causes bad content lines to be logged and ignored.
   */
  public static RecurrenceIterator createRecurrenceIterator(
      String rdata, DateValue dtStart, TimeZone tzid, boolean strict)
      throws ParseException {
    return createRecurrenceIterable(rdata, dtStart, tzid, strict).iterator();
  }

  public static RecurrenceIterable createRecurrenceIterable(
      String rdata, final DateValue dtStart, final TimeZone tzid,
      final boolean strict)
      throws ParseException {
    final IcalObject[] contentLines = parseContentLines(rdata, tzid, strict);

    return new RecurrenceIterable() {
	public RecurrenceIterator iterator() {
	  List<RecurrenceIterator> inclusions =
               new ArrayList<RecurrenceIterator>();
	  List<RecurrenceIterator> exclusions =
               new ArrayList<RecurrenceIterator>();
	  // always include DTStart
	  inclusions.add(new RDateIteratorImpl(
			     new DateValue[] {TimeUtils.toUtc(dtStart, tzid)}));
	  for (IcalObject contentLine : contentLines) {
	    try {
	      String name = contentLine.getName();
	      if ("rrule".equalsIgnoreCase(name)) {
		inclusions.add(createRecurrenceIterator(
                                   (RRule) contentLine, dtStart, tzid));
	      } else if ("rdate".equalsIgnoreCase(name)) {
		inclusions.add(
                    createRecurrenceIterator((RDateList) contentLine));
	      } else if ("exrule".equalsIgnoreCase(name)) {
		exclusions.add(createRecurrenceIterator(
                                   (RRule) contentLine, dtStart, tzid));
	      } else if ("exdate".equalsIgnoreCase(name)) {
		exclusions.add(
                    createRecurrenceIterator((RDateList) contentLine));
	      }
	    } catch (IllegalArgumentException ex) {
	      // bad frequency on rrule or exrule
	      if (strict) { throw ex; }
	      LOGGER.log(
		  Level.SEVERE,
		  "Dropping bad recurrence rule line: " + contentLine.toIcal(),
		  ex);
	    }
	  }
	  return new CompoundIteratorImpl(inclusions, exclusions);
	}
      };
  }

  /**
   * like {@link #createRecurrenceIterator(String,DateValue,TimeZone,boolean)}
   * but defaults to strict parsing.
   */
  public static RecurrenceIterator createRecurrenceIterator(
      String rdata, DateValue dtStart, TimeZone tzid)
      throws ParseException {
    return createRecurrenceIterator(rdata, dtStart, tzid, true);
  }

  /**
   * create a recurrence iterator from an rdate or exdate list.
   */
  public static RecurrenceIterator createRecurrenceIterator(RDateList rdates) {
    DateValue[] dates = rdates.getDatesUtc();
    Arrays.sort(dates);
    int k = 0;
    for (int i = 1; i < dates.length; ++i) {
      if (!dates[i].equals(dates[k])) { dates[++k] = dates[i]; }
    }
    if (++k < dates.length) {
      DateValue[] uniqueDates = new DateValue[k ];
      System.arraycopy(dates, 0, uniqueDates, 0, k);
      dates = uniqueDates;
    }
    return new RDateIteratorImpl(dates);
  }

  /**
   * create a recurrence iterator from an rrule.
   * @param rrule the recurrence rule to iterate.
   * @param dtStart the start of the series, in tzid.
   * @param tzid the timezone to iterate in.
   */
  public static RecurrenceIterator createRecurrenceIterator(
      RRule rrule, DateValue dtStart, TimeZone tzid) {
    assert null != tzid;
    assert null != dtStart;

    Frequency freq = rrule.getFreq();
    Weekday wkst = rrule.getWkSt();
    DateValue untilUtc = rrule.getUntil();
    int count = rrule.getCount();
    int interval = rrule.getInterval();
    WeekdayNum[] byDay = rrule.getByDay().toArray(new WeekdayNum[0]);
    int[] byMonth = rrule.getByMonth();
    int[] byMonthDay = rrule.getByMonthDay();
    int[] byWeekNo = rrule.getByWeekNo();
    int[] byYearDay = rrule.getByYearDay();
    int[] bySetPos = rrule.getBySetPos();
    int[] byHour = rrule.getByHour();
    int[] byMinute = rrule.getByMinute();
    int[] bySecond = rrule.getBySecond();

    // Make sure that BYMINUTE, BYHOUR, and BYSECOND rules are respected if they
    // have exactly one iteration, so not causing frequency to exceed daily.
    TimeValue startTime = null;
    if (1 == (byHour.length | byMinute.length | bySecond.length)
        && dtStart instanceof TimeValue) {
      TimeValue tv = (TimeValue) dtStart;
      startTime = new DateTimeValueImpl(
          0, 0, 0,
          1 == byHour.length ? byHour[0] : tv.hour(),
          1 == byMinute.length ? byMinute[0] : tv.minute(),
          1 == bySecond.length ? bySecond[0] : tv.second());
    }


    if (interval <= 0) {  interval = 1; }
    if (null == wkst) {
      wkst = Weekday.MO;
    }

    // recurrences are implemented as a sequence of periodic generators.
    // First a year is generated, and then months, and within months, days
    Generator yearGenerator = Generators.serialYearGenerator(
        freq == Frequency.YEARLY ? interval : 1, dtStart);
    Generator monthGenerator = null;
    Generator dayGenerator;

    // When multiple generators are specified for a period, they act as a union
    // operator.  We could have multiple generators (for day say) and then
    // run each and merge the results, but some generators are more efficient
    // than others, so to avoid generating 53 sundays and throwing away all but
    // 1 for RRULE:FREQ=YEARLY;BYDAY=TU;BYWEEKNO=1, we reimplement some of the
    // more prolific generators as filters.
    // TODO(msamuel): don't need a list here
    List<Predicate<? super DateValue>> filters =
      new ArrayList<Predicate<? super DateValue>>();

    // choose the appropriate generators and filters
    switch (freq) {
      case DAILY:
        if (0 == byMonthDay.length) {
          dayGenerator = Generators.serialDayGenerator(interval, dtStart);
        } else {
          dayGenerator = Generators.byMonthDayGenerator(byMonthDay, dtStart);
        }
        if (0 != byDay.length) {
          // TODO(msamuel): the spec is not clear on this.  Treat the week
          // numbers as weeks in the year.  This is only implemented for
          // conformance with libical.
          filters.add(Filters.byDayFilter(byDay, true, wkst));
        }
        break;
      case WEEKLY:
        // week is not considered a period because a week may span multiple
        // months &| years.  There are no week generators, but so a filter is
        // used to make sure that FREQ=WEEKLY;INTERVAL=2 only generates dates
        // within the proper week.
        if (0 != byDay.length) {
          dayGenerator = Generators.byDayGenerator(byDay, false, dtStart);
          if (interval > 1) {
            filters.add(Filters.weekIntervalFilter(interval, wkst, dtStart));
          }
        } else {
          dayGenerator = Generators.serialDayGenerator(interval * 7, dtStart);
        }
        if (0 != byMonthDay.length) {
          filters.add(Filters.byMonthDayFilter(byMonthDay));
        }
        break;
      case YEARLY:
        if (0 != byYearDay.length) {
          // The BYYEARDAY rule part specifies a COMMA separated list of days of
          // the year. Valid values are 1 to 366 or -366 to -1. For example, -1
          // represents the last day of the year (December 31st) and -306
          // represents the 306th to the last day of the year (March 1st).
          dayGenerator = Generators.byYearDayGenerator(byYearDay, dtStart);
          if (0 != byDay.length) {
            filters.add(Filters.byDayFilter(byDay, true, wkst));
          }
          if (0 != byMonthDay.length) {
            filters.add(Filters.byMonthDayFilter(byMonthDay));
          }
          // TODO(msamuel): filter byWeekNo and write unit tests
          break;
        }
        // fallthru to monthly cases
      case MONTHLY:
        if (0 != byMonthDay.length) {
          // The BYMONTHDAY rule part specifies a COMMA separated list of days
          // of the month. Valid values are 1 to 31 or -31 to -1. For example,
          // -10 represents the tenth to the last day of the month.
          dayGenerator = Generators.byMonthDayGenerator(byMonthDay, dtStart);
          if (0 != byDay.length) {
            filters.add(
                Filters.byDayFilter(byDay, Frequency.YEARLY == freq, wkst));
          }
          // TODO(msamuel): filter byWeekNo and write unit tests
        } else if (0 != byWeekNo.length && Frequency.YEARLY == freq) {
          // The BYWEEKNO rule part specifies a COMMA separated list of ordinals
          // specifying weeks of the year.  This rule part is only valid for
          // YEARLY rules.
          dayGenerator = Generators.byWeekNoGenerator(byWeekNo, wkst, dtStart);
          if (0 != byDay.length) {
            filters.add(Filters.byDayFilter(byDay, true, wkst));
          }
        } else if (0 != byDay.length) {
          // Each BYDAY value can also be preceded by a positive (n) or negative
          // (-n) integer. If present, this indicates the nth occurrence of the
          // specific day within the MONTHLY or YEARLY RRULE. For example,
          // within a MONTHLY rule, +1MO (or simply 1MO) represents the first
          // Monday within the month, whereas -1MO represents the last Monday of
          // the month. If an integer modifier is not present, it means all days
          // of this type within the specified frequency. For example, within a
          // MONTHLY rule, MO represents all Mondays within the month.
          dayGenerator = Generators.byDayGenerator(
              byDay, Frequency.YEARLY == freq && 0 == byMonth.length, dtStart);
        } else {
          if (Frequency.YEARLY == freq) {
            monthGenerator = Generators.byMonthGenerator(
                new int[] { dtStart.month() }, dtStart);
          }
          dayGenerator = Generators.byMonthDayGenerator(
              new int[] { dtStart.day() }, dtStart);
        }
        break;
      default:
        throw new IllegalArgumentException(
            "Can't iterate more frequently than daily");
    }

    // generator inference common to all periods
    if (0 != byMonth.length) {
      monthGenerator = Generators.byMonthGenerator(byMonth, dtStart);
    } else if (null == monthGenerator) {
      monthGenerator = Generators.serialMonthGenerator(
          freq == Frequency.MONTHLY ? interval : 1, dtStart);
    }

    // the condition tells the iterator when to halt.
    // The condition is exclusive, so the date that triggers it will not be
    // included.
    Predicate<DateValue> condition;
    boolean canShortcutAdvance = true;
    if (0 != count) {
      condition = Conditions.countCondition(count);
      // We can't shortcut because the countCondition must see every generated
      // instance.
      // TODO(msamuel): if count is large, we might try predicting the end date
      // so that we can convert the COUNT condition to an UNTIL condition.
      canShortcutAdvance = false;
    } else if (null != untilUtc) {
      if ((untilUtc instanceof TimeValue) != (dtStart instanceof TimeValue)) {
        // TODO(msamuel): warn
        if (dtStart instanceof TimeValue) {
          untilUtc = TimeUtils.dayStart(untilUtc);
        } else {
          untilUtc = TimeUtils.toDateValue(untilUtc);
        }
      }
      condition = Conditions.untilCondition(untilUtc);
    } else {
      condition = Predicates.<DateValue>alwaysTrue();
    }

    // combine filters into a single function
    Predicate<? super DateValue> filter;
    switch (filters.size()) {
      case 0:
        filter = Predicates.<DateValue>alwaysTrue();
        break;
      case 1:
        filter = filters.get(0);
        break;
      default:
        filter = Predicates.and(filters.toArray(new Predicate[0]));
        break;
    }

    Generator instanceGenerator;
    if (0 != bySetPos.length) {
      switch (freq) {
        case WEEKLY:
        case MONTHLY:
        case YEARLY:
          instanceGenerator = InstanceGenerators.bySetPosInstanceGenerator(
              bySetPos, freq, wkst, filter,
              yearGenerator, monthGenerator, dayGenerator);
          break;
        default:
          // TODO(msamuel): if we allow iteration more frequently than daily
          // then we will need to implement bysetpos for hours, minutes, and
          // seconds.  It should be sufficient though to simply choose the
          // instance of the set statically for every occurrence except the
          // first.
          // E.g. RRULE:FREQ=DAILY;BYHOUR=0,6,12,18;BYSETPOS=1
          // for DTSTART:20000101T130000
          // will yield
          // 20000101T180000
          // 20000102T000000
          // 20000103T000000
          // ...

          instanceGenerator = InstanceGenerators.serialInstanceGenerator(
              filter, yearGenerator, monthGenerator, dayGenerator);
          break;
      }
    } else {
      instanceGenerator = InstanceGenerators.serialInstanceGenerator(
          filter, yearGenerator, monthGenerator, dayGenerator);
    }

    return new RRuleIteratorImpl(
        dtStart, tzid, condition, filter, instanceGenerator,
        yearGenerator, monthGenerator, dayGenerator, canShortcutAdvance,
        startTime);
  }

  /**
   * a recurrence iterator that returns the union of the given recurrence
   * iterators.
   */
  public static RecurrenceIterator join(
      RecurrenceIterator a, RecurrenceIterator... b) {
    List<RecurrenceIterator> incl = new ArrayList<RecurrenceIterator>();
    incl.add(a);
    incl.addAll(Arrays.asList(b));
    return new CompoundIteratorImpl(
        incl, Collections.<RecurrenceIterator>emptyList());
  }

  /**
   * an iterator over all the dates included except those excluded, i.e.
   * <code>inclusions - exclusions</code>.
   * Exclusions trump inclusions, and {@link DateValue dates} and
   * {@link DateTimeValue date-times} never match one another.
   * @param included non null.
   * @param excluded non null.
   * @return non null.
   */
  public static RecurrenceIterator except(
      RecurrenceIterator included, RecurrenceIterator excluded) {
    return new CompoundIteratorImpl(
        Collections.<RecurrenceIterator>singleton(included),
        Collections.<RecurrenceIterator>singleton(excluded));
  }

  private static final Pattern FOLD = Pattern.compile("(?:\\r\\n?|\\n)[ \t]");
  private static final Pattern NEWLINE = Pattern.compile("[\\r\\n]+");
  private static final Pattern RULE = Pattern.compile(
      "^(?:R|EX)RULE[:;]", Pattern.CASE_INSENSITIVE);
  private static final Pattern DATE = Pattern.compile(
      "^(?:R|EX)DATE[:;]", Pattern.CASE_INSENSITIVE);
  private static IcalObject[] parseContentLines(
      String rdata, TimeZone tzid, boolean strict)
      throws ParseException {
    String unfolded = FOLD.matcher(rdata).replaceAll("").trim();
    if ("".equals(unfolded)) { return new IcalObject[0]; }
    String[] lines = NEWLINE.split(unfolded);
    IcalObject[] out = new IcalObject[lines.length];
    int nbad = 0;
    for (int i = 0; i < lines.length; ++i) {
      String line = lines[i].trim();
      try {
        if (RULE.matcher(line).find()) {
          out[i] = new RRule(line);
        } else if (DATE.matcher(line).find()) {
          out[i] = new RDateList(line, tzid);
        } else {
          throw new ParseException(lines[i], i);
        }
      } catch (ParseException ex) {
        if (strict) {
          throw ex;
        }
        LOGGER.log(Level.SEVERE,
                   "Dropping bad recurrence rule line: " + line, ex);
        ++nbad;
      } catch (IllegalArgumentException ex) {
        if (strict) {
          throw ex;
        }
        LOGGER.log(Level.SEVERE,
                   "Dropping bad recurrence rule line: " + line, ex);
        ++nbad;
      }
    }
    if (0 != nbad) {
      IcalObject[] trimmed = new IcalObject[out.length - nbad];
      for (int i = 0, k = 0; i < trimmed.length; ++k) {
        if (null != out[k]) { trimmed[i++] = out[k]; }
      }
      out = trimmed;
    }
    return out;
  }

  private RecurrenceIteratorFactory() {
    // uninstantiable
  }

}
