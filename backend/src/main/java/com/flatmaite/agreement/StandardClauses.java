package com.flatmaite.agreement;

import com.flatmaite.agreement.AgreementDtos.Clause;
import java.util.List;

/**
 * Standard clause library for a Maharashtra 11-month Leave & License agreement. Deliberately
 * state-scoped (MH first, per the product principle of supporting one state well); other states
 * plug in as additional libraries. None of this is legal advice and every rendering says so.
 */
public final class StandardClauses {

  private StandardClauses() {}

  public static List<Clause> forState(String state) {
    // Only MH ships in the MVP; unknown states get the MH baseline with a caveat clause.
    return List.of(
        new Clause(
            "rent",
            "Licence fee and escalation",
            "The Licensee shall pay the monthly licence fee on or before the 5th day of each calendar"
                + " month. The licence fee shall escalate by the agreed percentage on each anniversary"
                + " of the commencement date, where applicable.",
            "standard"),
        new Clause(
            "deposit",
            "Security deposit",
            "The Licensee shall place an interest-free refundable security deposit with the Licensor."
                + " The deposit shall be refunded within fifteen (15) days of vacation of the premises,"
                + " subject to deductions for unpaid dues and damage beyond normal wear and tear.",
            "standard"),
        new Clause(
            "lockin",
            "Lock-in period",
            "Neither party may terminate this agreement during the lock-in period except for material"
                + " breach. If the Licensee vacates during the lock-in period, the licence fee for the"
                + " remainder of the lock-in period shall be payable.",
            "standard"),
        new Clause(
            "notice",
            "Notice of termination",
            "After the lock-in period, either party may terminate this agreement by giving the agreed"
                + " written notice to the other party.",
            "standard"),
        new Clause(
            "maintenance",
            "Maintenance and society charges",
            "Monthly society maintenance charges shall be borne as agreed between the parties. Any"
                + " increase levied by the society during the term shall be borne in the same"
                + " proportion.",
            "standard"),
        new Clause(
            "utilities",
            "Utilities",
            "Electricity, gas, water, internet and other consumption-based utilities shall be paid by"
                + " the Licensee as per actual bills for the period of occupancy.",
            "standard"),
        new Clause(
            "use",
            "Use of premises",
            "The premises shall be used strictly for residential purposes by the named occupants"
                + " only. Subletting, assignment or commercial use is not permitted without prior"
                + " written consent of the Licensor.",
            "standard"),
        new Clause(
            "repairs",
            "Repairs",
            "Minor day-to-day repairs shall be borne by the Licensee. Structural repairs and major"
                + " repairs not attributable to the Licensee's negligence shall be borne by the"
                + " Licensor.",
            "standard"),
        new Clause(
            "registration",
            "Registration and stamp duty",
            "Under the Maharashtra Rent Control Act and the Registration Act, a leave and licence"
                + " agreement is required to be registered. Stamp duty and registration charges shall"
                + " be borne as mutually agreed (customarily shared equally). Government charges are"
                + " separate from any platform service fee.",
            "standard"));
  }

  /** Optional clause pool used by the mock advisor and as grounding examples for the LLM. */
  public static List<Clause> optionalPool() {
    return List.of(
        new Clause(
            "pets",
            "Pets",
            "The Licensee may keep pets only with the prior written consent of the Licensor and in"
                + " compliance with housing society rules. The Licensee is responsible for any damage"
                + " caused by pets.",
            "ai"),
        new Clause(
            "guests",
            "Guests",
            "Short-term guests are permitted. Any occupant staying beyond fifteen (15) consecutive"
                + " days shall be disclosed to the Licensor in writing.",
            "ai"),
        new Clause(
            "parking",
            "Parking",
            "The Licensee is entitled to the use of the designated parking slot, if any, allotted to"
                + " the premises, subject to society rules.",
            "ai"),
        new Clause(
            "inventory",
            "Inventory of furnishings",
            "An inventory of furniture, fixtures and appliances signed by both parties is annexed to"
                + " this agreement. The Licensee shall return all items in working condition, normal"
                + " wear and tear excepted.",
            "ai"),
        new Clause(
            "painting",
            "Painting charges",
            "At vacation, reasonable one-time painting or deep-cleaning charges as agreed may be"
                + " deducted from the security deposit in lieu of restoration.",
            "ai"),
        new Clause(
            "society",
            "Society rules",
            "The Licensee shall abide by the byelaws and rules of the housing society, including"
                + " rules regarding noise, common areas and move-in/move-out timings.",
            "ai"));
  }
}
